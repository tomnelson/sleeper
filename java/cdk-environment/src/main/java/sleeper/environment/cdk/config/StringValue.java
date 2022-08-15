/*
 * Copyright 2022 Crown Copyright
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
package sleeper.environment.cdk.config;

import java.util.Objects;

public class StringValue {

    private final String key;
    private final String value;

    StringValue(String key, String value) {
        this.key = key;
        this.value = value;
    }

    boolean hasKey(String key) {
        return Objects.equals(this.key, key);
    }

    String getValue() {
        return value;
    }
}
