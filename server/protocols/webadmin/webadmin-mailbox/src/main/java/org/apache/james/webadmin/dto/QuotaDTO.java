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


package org.apache.james.webadmin.dto;

import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.common.base.MoreObjects;

@JsonDeserialize(builder = QuotaDTO.Builder.class)
public class QuotaDTO {

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private Optional<Long> count;
        private Optional<Long> size;

        private Builder() {
            count = Optional.empty();
            size = Optional.empty();
        }

        public Builder count(Optional<Long> count) {
            this.count = count;
            return this;
        }

        public Builder size(Optional<Long> size) {
            this.size = size;
            return this;
        }

        public QuotaDTO build() {
            return new QuotaDTO(count, size);
        }
    }

    private final Optional<Long> count;
    private final Optional<Long> size;

    private QuotaDTO(Optional<Long> count, Optional<Long> size) {
        this.count = count;
        this.size = size;
    }

    public Optional<Long> getCount() {
        return count;
    }

    public Optional<Long> getSize() {
        return size;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof QuotaDTO) {
            QuotaDTO that = (QuotaDTO) o;

            return Objects.equals(this.count, that.count) &&
                Objects.equals(this.size, that.size);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(count, size);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("count", count)
            .add("size", size)
            .toString();
    }


}
