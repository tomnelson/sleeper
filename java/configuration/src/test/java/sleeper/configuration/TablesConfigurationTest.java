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
package sleeper.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.core.schema.Field;
import sleeper.core.schema.Schema;
import sleeper.core.schema.type.StringType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sleeper.configuration.properties.InstancePropertiesTestHelper.createTestInstanceProperties;
import static sleeper.configuration.properties.table.TablePropertiesTestHelper.createTestTableProperties;
import static sleeper.configuration.properties.table.TablePropertiesTestHelper.createTestTablePropertiesWithNoSchema;

class TablesConfigurationTest {

    private final InstanceProperties instanceProperties = createTestInstanceProperties();
    @TempDir
    private Path tempDir;
    private Path instancePropertiesFile;

    @BeforeEach
    void setUp() throws IOException {
        instancePropertiesFile = tempDir.resolve("instance.properties");
        instanceProperties.save(instancePropertiesFile);
    }

    private Schema schemaWithKey(String keyName) {
        return Schema.builder().rowKeyFields(new Field(keyName, new StringType())).build();
    }

    @Test
    void shouldLoadTablePropertiesFileNextToInstancePropertiesFile() throws IOException {
        // Given
        TableProperties properties = createTestTablePropertiesWithNoSchema(instanceProperties);
        properties.save(tempDir.resolve("table.properties"));
        Schema schema = schemaWithKey("test-key");
        schema.save(tempDir.resolve("schema.json"));

        // When
        TablesConfiguration tablesConfiguration = TablesConfiguration.loadFromPath(instancePropertiesFile, instanceProperties);

        // Then
        properties.setSchema(schema);
        assertThat(tablesConfiguration.getTables())
                .containsExactly(properties);
    }

    @Test
    void shouldLoadTablePropertiesFilesInTablesFolder() throws IOException {
        // Given
        Files.createDirectories(tempDir.resolve("tables/table1"));
        Files.createDirectory(tempDir.resolve("tables/table2"));

        TableProperties properties1 = createTestTablePropertiesWithNoSchema(instanceProperties);
        properties1.save(tempDir.resolve("tables/table1/table.properties"));
        TableProperties properties2 = createTestTablePropertiesWithNoSchema(instanceProperties);
        properties2.save(tempDir.resolve("tables/table2/table.properties"));

        Schema schema1 = schemaWithKey("test-key1");
        schema1.save(tempDir.resolve("tables/table1/schema.json"));
        Schema schema2 = schemaWithKey("test-key2");
        schema2.save(tempDir.resolve("tables/table2/schema.json"));

        // When
        TablesConfiguration tablesConfiguration = TablesConfiguration.loadFromPath(instancePropertiesFile, instanceProperties);

        // Then
        properties1.setSchema(schema1);
        properties2.setSchema(schema2);
        assertThat(tablesConfiguration.getTables())
                .containsExactly(properties1, properties2);
    }

    @Test
    void shouldFailToLoadTablePropertiesFilesWhenNonePresent() {
        // When
        TablesConfiguration tablesConfiguration = TablesConfiguration.loadFromPath(instancePropertiesFile, instanceProperties);

        // Then
        assertThat(tablesConfiguration.getTables())
                .isEmpty();
    }

    @Test
    void shouldFailToLoadTablePropertiesWhenInstancePropertiesDirectoryNotSpecified() {
        // When
        TablesConfiguration tablesConfiguration = TablesConfiguration.loadFromPath(instancePropertiesFile, instanceProperties);

        // Then
        assertThat(tablesConfiguration.getTables())
                .isEmpty();
    }

    @Test
    void shouldFailToLoadTablePropertiesWhenNoSchemaSpecified() throws IOException {
        // Given
        TableProperties properties = createTestTablePropertiesWithNoSchema(instanceProperties);
        properties.save(tempDir.resolve("table.properties"));

        // When/Then
        assertThatThrownBy(() -> TablesConfiguration.loadFromPath(instancePropertiesFile, instanceProperties))
                .hasMessage("Schema not set in property sleeper.table.schema");
    }

    @Test
    void shouldLoadTablePropertiesFileNextToInstancePropertiesFileWithSchemaInProperties() throws IOException {
        // Given
        TableProperties properties = createTestTableProperties(instanceProperties, schemaWithKey("test-key"));
        properties.save(tempDir.resolve("table.properties"));

        // When
        TablesConfiguration tablesConfiguration = TablesConfiguration.loadFromPath(instancePropertiesFile, instanceProperties);

        // Then
        assertThat(tablesConfiguration.getTables())
                .containsExactly(properties);
    }

    @Test
    void shouldLoadTablePropertiesFilesInTablesFolderWithSchemaInProperties() throws IOException {
        // Given
        Files.createDirectories(tempDir.resolve("tables/table1"));
        Files.createDirectory(tempDir.resolve("tables/table2"));
        TableProperties properties1 = createTestTableProperties(instanceProperties, schemaWithKey("test-key1"));
        properties1.save(tempDir.resolve("tables/table1/table.properties"));
        TableProperties properties2 = createTestTableProperties(instanceProperties, schemaWithKey("test-key2"));
        properties2.save(tempDir.resolve("tables/table2/table.properties"));

        // When
        TablesConfiguration tablesConfiguration = TablesConfiguration.loadFromPath(instancePropertiesFile, instanceProperties);

        // Then
        assertThat(tablesConfiguration.getTables())
                .containsExactly(properties1, properties2);
    }

    @Test
    void shouldSaveTablePropertiesFilesInTablesFolder() {
        // Given
        TableProperties properties1 = createTestTablePropertiesWithNoSchema(instanceProperties, "test-table-1");
        TableProperties properties2 = createTestTablePropertiesWithNoSchema(instanceProperties, "test-table-2");
        Schema schema1 = schemaWithKey("test-key1");
        Schema schema2 = schemaWithKey("test-key2");
        properties1.setSchema(schema1);
        properties2.setSchema(schema2);

        TablesConfiguration tablesConfiguration = TablesConfiguration.fromTables(properties1, properties2);

        // When
        tablesConfiguration.saveToPath(instancePropertiesFile);

        // Then
        assertThat(Files.exists(tempDir.resolve("tables/test-table-1/table.properties")))
                .isTrue();
        assertThat(Files.exists(tempDir.resolve("tables/test-table-1/schema.json")))
                .isTrue();
        assertThat(Files.exists(tempDir.resolve("tables/test-table-2/table.properties")))
                .isTrue();
        assertThat(Files.exists(tempDir.resolve("tables/test-table-2/schema.json")))
                .isTrue();
    }

    @Test
    void shouldFailToSaveTablePropertiesFilesWhenSchemaNotSpecified() {
        // Given
        TableProperties properties1 = createTestTablePropertiesWithNoSchema(instanceProperties, "test-table-1");

        TablesConfiguration tablesConfiguration = TablesConfiguration.fromTables(properties1);

        // When/Then
        assertThatThrownBy(() -> tablesConfiguration.saveToPath(instancePropertiesFile))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
