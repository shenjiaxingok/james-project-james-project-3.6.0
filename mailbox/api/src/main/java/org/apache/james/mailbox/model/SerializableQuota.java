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

package org.apache.james.mailbox.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

import org.apache.james.core.quota.QuotaLimitValue;
import org.apache.james.core.quota.QuotaUsageValue;

import com.google.common.base.MoreObjects;

public class SerializableQuota<T extends QuotaLimitValue<T>, U extends QuotaUsageValue<U, T>> implements Serializable {

    public static final long UNLIMITED = -1;

    public static <T extends QuotaLimitValue<T>, U extends QuotaUsageValue<U, T>> SerializableQuota<T, U> newInstance(Quota<T, U> quota) {
        return newInstance(quota.getUsed(), quota.getLimit());
    }

    public static <T extends QuotaLimitValue<T>, U extends QuotaUsageValue<U, T>> SerializableQuota<T, U> newInstance(U used, T max) {
        return new SerializableQuota<>(
            new SerializableQuotaUsageValue<>(used),
            new SerializableQuotaLimitValue<>(max)
        );
    }

    private final SerializableQuotaLimitValue<T> max;
    private final SerializableQuotaUsageValue<T, U> used;

    private SerializableQuota(SerializableQuotaUsageValue<T, U> used, SerializableQuotaLimitValue<T> max) {
        this.max = max;
        this.used = used;
    }

    public Long encodeAsLong() {
        return max.encodeAsLong();
    }

    public Long getUsed() {
        return Optional.ofNullable(used).map(SerializableQuotaUsageValue::encodeAsLong).orElse(null);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof SerializableQuota<?, ?>) {
            SerializableQuota<?, ?> that = (SerializableQuota<?,?>) o;
            return Objects.equals(max, that.max) &&
                Objects.equals(used, that.used);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(max, used);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("max", max)
            .add("used", used)
            .toString();
    }
}
