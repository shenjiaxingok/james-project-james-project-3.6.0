/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 * http://www.apache.org/licenses/LICENSE-2.0                   *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.jmap.routes

import javax.inject.Inject
import org.apache.james.core.Username
import org.apache.james.jmap.core.CapabilityIdentifier.CapabilityIdentifier
import org.apache.james.jmap.core.{Account, AccountId, Capabilities, Capability, IsPersonal, IsReadOnly, JmapRfc8621Configuration, Session}

import scala.jdk.CollectionConverters._

class SessionSupplier(val configuration: JmapRfc8621Configuration, defaultCapabilities: Set[Capability]) {
  @Inject
  def this(configuration: JmapRfc8621Configuration, defaultCapabilities: java.util.Set[Capability]) {
    this(configuration, defaultCapabilities.asScala.toSet)
  }

  def generate(username: Username): Either[IllegalArgumentException, Session] =
    accounts(username)
      .map(account => Session(
        Capabilities(defaultCapabilities),
        List(account),
        primaryAccounts(account.accountId),
        username,
        apiUrl = configuration.apiUrl,
        downloadUrl = configuration.downloadUrl,
        uploadUrl = configuration.uploadUrl,
        eventSourceUrl = configuration.eventSourceUrl))

  private def accounts(username: Username): Either[IllegalArgumentException, Account] =
    Account.from(username, IsPersonal(true), IsReadOnly(false), defaultCapabilities)

  private def primaryAccounts(accountId: AccountId): Map[CapabilityIdentifier, AccountId] =
    defaultCapabilities
      .map(capability => (capability.identifier(), accountId))
      .toMap
}
