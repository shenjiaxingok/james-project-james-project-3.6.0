/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.mailbox.cassandra.mail;

import static org.apache.james.backends.cassandra.Scenario.Builder.awaitOn;
import static org.apache.james.backends.cassandra.Scenario.Builder.executeNormally;
import static org.apache.james.mailbox.cassandra.table.CassandraMessageModseqTable.MAILBOX_ID;
import static org.apache.james.mailbox.cassandra.table.CassandraMessageModseqTable.TABLE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import org.apache.james.backends.cassandra.CassandraCluster;
import org.apache.james.backends.cassandra.CassandraClusterExtension;
import org.apache.james.backends.cassandra.Scenario.Barrier;
import org.apache.james.backends.cassandra.init.configuration.CassandraConfiguration;
import org.apache.james.core.Username;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.cassandra.ids.CassandraId;
import org.apache.james.mailbox.cassandra.modules.CassandraModSeqModule;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.UidValidity;
import org.apache.james.util.concurrency.ConcurrentTestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.github.fge.lambdas.Throwing;

import reactor.core.scheduler.Schedulers;

class CassandraModSeqProviderTest {
    private static final CassandraId CASSANDRA_ID = new CassandraId.Factory().fromString("e22b3ac0-a80b-11e7-bb00-777268d65503");

    @RegisterExtension
    static CassandraClusterExtension cassandraCluster = new CassandraClusterExtension(CassandraModSeqModule.MODULE);
    
    private CassandraModSeqProvider modSeqProvider;
    private Mailbox mailbox;


    @BeforeEach
    void setUp(CassandraCluster cassandra) {
        modSeqProvider = new CassandraModSeqProvider(
            cassandra.getConf(),
            CassandraConfiguration.DEFAULT_CONFIGURATION,
            cassandraCluster.getCassandraConsistenciesConfiguration());
        MailboxPath path = new MailboxPath("gsoc", Username.of("ieugen"), "Trash");
        mailbox = new Mailbox(path, UidValidity.of(1234), CASSANDRA_ID);
    }

    @Test
    void highestModSeqShouldRetrieveValueStoredNextModSeq() throws Exception {
        int nbEntries = 100;
        ModSeq result = modSeqProvider.highestModSeq(mailbox);
        assertThat(result).isEqualTo(ModSeq.first());
        LongStream.range(0, nbEntries)
            .forEach(Throwing.longConsumer(value -> {
                    ModSeq modSeq = modSeqProvider.nextModSeq(mailbox);
                    assertThat(modSeq).isEqualTo(modSeqProvider.highestModSeq(mailbox));
                })
            );
    }

    @Test
    void nextModSeqShouldIncrementValueByOne() throws Exception {
        int nbEntries = 100;
        ModSeq lastModSeq = modSeqProvider.highestModSeq(mailbox);
        LongStream.range(lastModSeq.asLong() + 1, lastModSeq.asLong() + nbEntries)
            .forEach(Throwing.longConsumer(value -> {
                ModSeq result = modSeqProvider.nextModSeq(mailbox);
                assertThat(result.asLong()).isEqualTo(value);
            }));
    }

    @Test
    void failedInsertsShouldBeRetried(CassandraCluster cassandra) throws Exception {
        Barrier insertBarrier = new Barrier(2);
        Barrier retryBarrier = new Barrier(1);
        cassandra.getConf()
            .registerScenario(
                executeNormally()
                    .times(2)
                    .whenQueryStartsWith("SELECT nextModseq FROM modseq WHERE mailboxId=:mailboxId;"),
                awaitOn(insertBarrier)
                    .thenExecuteNormally()
                    .times(2)
                    .whenQueryStartsWith("INSERT INTO modseq (nextModseq,mailboxId) VALUES (:nextModseq,:mailboxId) IF NOT EXISTS;"),
                awaitOn(retryBarrier)
                    .thenExecuteNormally()
                    .times(1)
                    .whenQueryStartsWith("SELECT nextModseq FROM modseq WHERE mailboxId=:mailboxId;"));

        CompletableFuture<ModSeq> operation1 = modSeqProvider.nextModSeq(CASSANDRA_ID)
            .subscribeOn(Schedulers.elastic())
            .toFuture();
        CompletableFuture<ModSeq> operation2 = modSeqProvider.nextModSeq(CASSANDRA_ID)
            .subscribeOn(Schedulers.elastic())
            .toFuture();

        insertBarrier.awaitCaller();
        insertBarrier.releaseCaller();

        retryBarrier.awaitCaller();
        retryBarrier.releaseCaller();

        // Artificially fail the insert failure
        cassandra.getConf()
            .execute(QueryBuilder.delete().from(TABLE_NAME)
                .where(QueryBuilder.eq(MAILBOX_ID, CASSANDRA_ID.asUuid())));

        retryBarrier.releaseCaller();

        assertThatCode(() -> operation1.get(1, TimeUnit.SECONDS)).doesNotThrowAnyException();
        assertThatCode(() -> operation2.get(1, TimeUnit.SECONDS)).doesNotThrowAnyException();
    }

    @Test
    void nextModSeqShouldGenerateUniqueValuesWhenParallelCalls() throws ExecutionException, InterruptedException {
        int nbEntries = 10;

        ConcurrentSkipListSet<ModSeq> modSeqs = new ConcurrentSkipListSet<>();
        ConcurrentTestRunner.builder()
            .operation(
                (threadNumber, step) -> modSeqs.add(modSeqProvider.nextModSeq(mailbox)))
            .threadCount(10)
            .operationCount(nbEntries)
            .runSuccessfullyWithin(Duration.ofMinutes(1));

        assertThat(modSeqs).hasSize(100);
    }
}
