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

package org.apache.james.quota.search.elasticsearch.v7.events;

import static org.apache.james.quota.search.QuotaSearchFixture.TestConstants.BOB_USERNAME;
import static org.apache.james.quota.search.QuotaSearchFixture.TestConstants.NOW;
import static org.apache.james.quota.search.QuotaSearchFixture.TestConstants.QUOTAROOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Durations.ONE_HUNDRED_MILLISECONDS;

import java.io.IOException;

import org.apache.james.backends.es.v7.DockerElasticSearchExtension;
import org.apache.james.backends.es.v7.ElasticSearchConfiguration;
import org.apache.james.backends.es.v7.ElasticSearchIndexer;
import org.apache.james.backends.es.v7.ReactorElasticSearchClient;
import org.apache.james.events.Event;
import org.apache.james.events.Group;
import org.apache.james.mailbox.quota.QuotaFixture.Counts;
import org.apache.james.mailbox.quota.QuotaFixture.Sizes;
import org.apache.james.mailbox.store.event.EventFactory;
import org.apache.james.quota.search.elasticsearch.v7.QuotaRatioElasticSearchConstants;
import org.apache.james.quota.search.elasticsearch.v7.QuotaSearchIndexCreationUtil;
import org.apache.james.quota.search.elasticsearch.v7.UserRoutingKeyFactory;
import org.apache.james.quota.search.elasticsearch.v7.json.QuotaRatioToElasticSearchJson;
import org.awaitility.Awaitility;
import org.awaitility.Durations;
import org.awaitility.core.ConditionFactory;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ElasticSearchQuotaMailboxListenerTest {

    private static final ConditionFactory CALMLY_AWAIT = Awaitility
        .with().pollInterval(ONE_HUNDRED_MILLISECONDS)
        .and().pollDelay(ONE_HUNDRED_MILLISECONDS)
        .await();
    static Event.EventId EVENT_ID = Event.EventId.of("6e0dd59d-660e-4d9b-b22f-0354479f47b4");

    @RegisterExtension
    DockerElasticSearchExtension elasticSearch = new DockerElasticSearchExtension();

    ElasticSearchQuotaMailboxListener quotaMailboxListener;
    ReactorElasticSearchClient client;

    @BeforeEach
    void setUp() throws IOException {
        client = elasticSearch.getDockerElasticSearch().clientProvider().get();

        QuotaSearchIndexCreationUtil.prepareDefaultClient(client, ElasticSearchConfiguration.builder()
            .addHost(elasticSearch.getDockerElasticSearch().getHttpHost())
            .build());

        quotaMailboxListener = new ElasticSearchQuotaMailboxListener(
            new ElasticSearchIndexer(client,
                QuotaRatioElasticSearchConstants.DEFAULT_QUOTA_RATIO_WRITE_ALIAS),
            new QuotaRatioToElasticSearchJson(),
            new UserRoutingKeyFactory());
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
    }

    @Test
    void deserializeElasticSearchQuotaMailboxListenerGroup() throws Exception {
        assertThat(Group.deserialize("org.apache.james.quota.search.elasticsearch.v7.events.ElasticSearchQuotaMailboxListener$ElasticSearchQuotaMailboxListenerGroup"))
            .isEqualTo(new ElasticSearchQuotaMailboxListener.ElasticSearchQuotaMailboxListenerGroup());
    }

    @Test
    void eventShouldIndexEventWhenQuotaEvent() throws Exception {
        quotaMailboxListener.event(EventFactory.quotaUpdated()
            .eventId(EVENT_ID)
            .user(BOB_USERNAME)
            .quotaRoot(QUOTAROOT)
            .quotaCount(Counts._52_PERCENT)
            .quotaSize(Sizes._55_PERCENT)
            .instant(NOW)
            .build());

        CALMLY_AWAIT.atMost(Durations.TEN_SECONDS)
            .untilAsserted(() -> assertThat(client.search(
                new SearchRequest(QuotaRatioElasticSearchConstants.DEFAULT_QUOTA_RATIO_READ_ALIAS.getValue())
                    .source(new SearchSourceBuilder().query(QueryBuilders.matchAllQuery())),
                RequestOptions.DEFAULT)
                .block()
                .getHits().getTotalHits().value).isEqualTo(1));
    }
}