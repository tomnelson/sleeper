/*
 * Copyright 2022-2023 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sleeper.build.github.containers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonSerialize
public class TestGHCRImage {

    private final int id;
    private final Instant updatedAt;
    private final List<String> tags;

    private TestGHCRImage(Builder builder) {
        id = builder.id;
        updatedAt = builder.updatedAt;
        tags = builder.tags;
    }

    public static Builder image() {
        return new Builder();
    }

    public static TestGHCRImage imageWithId(int id) {
        return image().id(id).build();
    }

    public static TestGHCRImage imageWithIdAndTags(int id, String... tags) {
        return image().id(id).tags(tags).build();
    }

    public int getId() {
        return id;
    }

    @JsonProperty("updated_at")
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Map<String, Object> getMetadata() {
        return Map.of("package_type", "container", "container", Map.of("tags", tags));
    }

    public static final class Builder {
        private int id;
        private Instant updatedAt = Instant.parse("2023-01-01T12:00:01Z");
        private List<String> tags = List.of();

        private Builder() {
        }

        public Builder id(int id) {
            this.id = id;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public Builder tags(String... tags) {
            return tags(List.of(tags));
        }

        public TestGHCRImage build() {
            return new TestGHCRImage(this);
        }
    }
}
