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

package sleeper.clients.admin.deploy;

import org.junit.jupiter.api.Test;

import sleeper.configuration.properties.InstanceProperties;
import sleeper.util.RunCommand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static sleeper.configuration.properties.InstancePropertiesTestHelper.createTestInstanceProperties;
import static sleeper.configuration.properties.SleeperProperties.loadProperties;

class CdkDeployInstanceTest {
    @Test
    void shouldRunStandardCdkDeploySuccessfully() throws IOException, InterruptedException {
        // Given
        CdkDeployInstance cdk = CdkDeployInstance.builder()
                .instancePropertiesFile(Path.of("instance.properties"))
                .jarsDirectory(Path.of("."))
                .version("1.0")
                .ensureNewInstance(false).build();

        // Then
        assertThat(commandRunOnDeployOf(cdk, CdkDeployInstance.Type.STANDARD))
                .containsExactly("cdk",
                        "-a", "java -cp \"./cdk-1.0.jar\" sleeper.cdk.SleeperCdkApp",
                        "deploy",
                        "--require-approval", "never",
                        "-c", "propertiesfile=instance.properties",
                        "-c", "newinstance=false",
                        "*");
    }

    @Test
    void shouldRunSystemTestCdkDeploySuccessfully() throws IOException, InterruptedException {
        // Given
        CdkDeployInstance cdk = CdkDeployInstance.builder()
                .instancePropertiesFile(Path.of("instance.properties"))
                .jarsDirectory(Path.of("."))
                .version("1.0")
                .ensureNewInstance(false).build();

        // Then
        assertThat(commandRunOnDeployOf(cdk, CdkDeployInstance.Type.SYSTEM_TEST))
                .containsExactly("cdk",
                        "-a", "java -cp \"./system-test-1.0-utility.jar\" sleeper.systemtest.cdk.SystemTestApp",
                        "deploy",
                        "--require-approval", "never",
                        "-c", "propertiesfile=instance.properties",
                        "-c", "newinstance=false",
                        "*");
    }

    @Test
    void shouldInferStandardDeploymentWhenNoSystemTestPropertiesAreSet() throws IOException, InterruptedException {
        // Given
        CdkDeployInstance cdk = CdkDeployInstance.builder()
                .instancePropertiesFile(Path.of("instance.properties"))
                .jarsDirectory(Path.of("."))
                .version("1.0")
                .ensureNewInstance(false).build();

        InstanceProperties instanceProperties = createTestInstanceProperties();

        // Then
        assertThat(commandRunOn(runner -> cdk.deployInferringType(instanceProperties, runner)))
                .startsWith("cdk",
                        "-a", "java -cp \"./cdk-1.0.jar\" sleeper.cdk.SleeperCdkApp");
    }

    @Test
    void shouldInferSystemTestDeploymentWhenSystemTestPropertyIsSet() throws IOException, InterruptedException {
        // Given
        CdkDeployInstance cdk = CdkDeployInstance.builder()
                .instancePropertiesFile(Path.of("instance.properties"))
                .jarsDirectory(Path.of("."))
                .version("1.0")
                .ensureNewInstance(false).build();

        InstanceProperties instanceProperties = new InstanceProperties(loadProperties(
                createTestInstanceProperties().saveAsString() + "\n" +
                        "sleeper.systemtest.writers=123"));

        // Then
        assertThat(commandRunOn(runner -> cdk.deployInferringType(instanceProperties, runner)))
                .startsWith("cdk",
                        "-a", "java -cp \"./system-test-1.0-utility.jar\" sleeper.systemtest.cdk.SystemTestApp");
    }

    private String[] commandRunOnDeployOf(CdkDeployInstance cdk, CdkDeployInstance.Type instanceType) throws IOException, InterruptedException {
        return commandRunOn(runner -> cdk.deploy(instanceType, runner));
    }

    private String[] commandRunOn(Deploy deploy) throws IOException, InterruptedException {
        AtomicReference<String[]> reference = new AtomicReference<>();
        RunCommand runCommand = (args) -> {
            reference.set(args);
            return 0;
        };
        deploy.run(runCommand);
        return reference.get();
    }

    interface Deploy {
        void run(RunCommand runCommand) throws IOException, InterruptedException;
    }
}