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

package sleeper.cdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.local.SaveLocalProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sleeper.cdk.Utils.getVersion;
import static sleeper.cdk.UtilsTestHelper.cdkContextWithPropertiesFile;
import static sleeper.cdk.UtilsTestHelper.cdkContextWithPropertiesFileAndSkipVersionCheck;
import static sleeper.cdk.UtilsTestHelper.createInstancePropertiesWithVersion;
import static sleeper.cdk.UtilsTestHelper.createUserDefinedInstanceProperties;

public class UtilsVersionTest {
    @TempDir
    private Path tempDir;

    @Test
    void shouldGetVersionAsResource() {
        assertThat(getVersion())
                .matches(Pattern.compile("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"));
    }

    @Test
    void shouldSucceedVersionCheckWhenLocalVersionMatchesDeployedVersion() throws IOException {
        // Given
        InstanceProperties instanceProperties = createInstancePropertiesWithVersion(getVersion());
        SaveLocalProperties.saveToDirectory(tempDir, instanceProperties, Stream.empty());

        // When/Then
        assertThatCode(() -> Utils.loadInstanceProperties(new InstanceProperties(),
                cdkContextWithPropertiesFile(tempDir)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFailVersionCheckWhenLocalVersionDoesNotMatchDeployedVersion() throws IOException {
        // Given
        InstanceProperties instanceProperties = createInstancePropertiesWithVersion("0.14.0-SNAPSHOT");
        SaveLocalProperties.saveToDirectory(tempDir, instanceProperties, Stream.empty());

        // When/Then
        assertThatThrownBy(() -> Utils.loadInstanceProperties(new InstanceProperties(),
                cdkContextWithPropertiesFile(tempDir)))
                .isInstanceOf(MismatchedVersionException.class)
                .hasMessage("Local version " + getVersion() + " does not match deployed version 0.14.0-SNAPSHOT. " +
                        "Please upgrade/downgrade to make these match");
    }

    @Test
    void shouldSkipVersionCheckWhenLocalVersionDoesNotMatchDeployedVersion() throws IOException {
        // Given
        InstanceProperties instanceProperties = createInstancePropertiesWithVersion("0.14.0-SNAPSHOT");
        SaveLocalProperties.saveToDirectory(tempDir, instanceProperties, Stream.empty());

        // When/Then
        assertThatCode(() -> Utils.loadInstanceProperties(new InstanceProperties(),
                cdkContextWithPropertiesFileAndSkipVersionCheck(tempDir)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldSkipVersionCheckWhenDeployingNewInstance() throws IOException {
        // Given
        InstanceProperties instanceProperties = createUserDefinedInstanceProperties();
        SaveLocalProperties.saveToDirectory(tempDir, instanceProperties, Stream.empty());

        // When/Then
        assertThatCode(() -> Utils.loadInstanceProperties(new InstanceProperties(),
                cdkContextWithPropertiesFile(tempDir)))
                .doesNotThrowAnyException();
    }
}