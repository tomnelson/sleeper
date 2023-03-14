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
package sleeper.configuration.properties.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.SleeperProperties;
import sleeper.configuration.properties.SleeperProperty;
import sleeper.configuration.properties.SystemDefinedInstanceProperty;
import sleeper.configuration.properties.UserDefinedInstanceProperty;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.core.schema.Field;
import sleeper.core.schema.Schema;
import sleeper.core.schema.type.LongType;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static sleeper.configuration.properties.PropertiesUtils.loadProperties;
import static sleeper.configuration.properties.table.TablePropertiesTestHelper.createTestTableProperties;
import static sleeper.configuration.properties.table.TablePropertiesTestHelper.createTestTablePropertiesWithNoSchema;
import static sleeper.configuration.properties.table.TableProperty.SCHEMA;

class SleeperPropertiesPrettyPrinterTest {

    @Nested
    @DisplayName("Print properties")
    class PrintProperties {
        @Test
        void shouldPrintAllInstanceProperties() throws Exception {
            // When / Then
            assertThat(printEmptyInstanceProperties())
                    // Check all the user defined properties are present in the output
                    .contains(UserDefinedInstanceProperty.getAll().stream()
                            .map(UserDefinedInstanceProperty::getPropertyName)
                            .collect(Collectors.toList()))
                    // Check at least one system-defined property is present in the output
                    .containsAnyOf(SystemDefinedInstanceProperty.getAll().stream()
                            .map(SystemDefinedInstanceProperty::getPropertyName)
                            .toArray(String[]::new));
        }

        @Test
        void shouldPrintPropertyDescriptionWithMultipleLines() throws Exception {
            // When / Then
            assertThat(printInstanceProperties("sleeper.default.gc.delay.seconds=123"))
                    .contains("# A file will not be deleted until this number of seconds have passed after it has been marked as\n" +
                            "# ready for garbage collection. The reason for not deleting files immediately after they have been\n" +
                            "# marked as ready for garbage collection is that they may still be in use by queries. This property\n" +
                            "# can be overridden on a per-table basis.\n" +
                            "sleeper.default.gc.delay.seconds");
        }

        @Test
        void shouldPrintPropertyDescriptionWithCustomLineBreaks() throws Exception {
            // When / Then
            assertThat(printInstanceProperties("sleeper.ingest.partition.file.writer.type=direct"))
                    .contains("# The way in which partition files are written to the main Sleeper store.\n" +
                            "# Valid values are 'direct' (which writes using the s3a Hadoop file system) and 'async' (which writes\n" +
                            "# locally and then copies the completed Parquet file asynchronously into S3).\n" +
                            "# The direct method is simpler but the async method should provide better performance when the number\n" +
                            "# of partitions is large.\n" +
                            "sleeper.ingest.partition.file.writer.type");
        }

        @Test
        void shouldPrintPropertiesInTheCorrectOrder() throws IOException {
            // When
            String output = printEmptyInstanceProperties();

            // Then
            assertThat(output.indexOf("sleeper.account"))
                    .isLessThan(output.indexOf("sleeper.log.retention.days"))
                    .isLessThan(output.indexOf("sleeper.vpc"));
            assertThat(output.indexOf("sleeper.ingest"))
                    .isLessThan(output.indexOf("sleeper.compaction"));
        }
    }

    @Nested
    @DisplayName("Print values")
    class PrintValues {
        @Test
        void shouldPrintPropertyValueWithDescription() throws Exception {
            // When / Then
            assertThat(printInstanceProperties("sleeper.account=1234567890"))
                    .contains("# The AWS account number. This is the AWS account that the instance will be deployed to.\n" +
                            "sleeper.account=1234567890\n");
        }

        @Test
        void shouldPrintTableSchema() {
            // Given
            String schema = "{\"rowKeyFields\":[{\"name\":\"key\",\"type\":\"LongType\"}],\"sortKeyFields\":[],\"valueFields\":[]}";
            // When / Then
            assertThat(printTableProperties(Schema.loadFromString(schema)))
                    .contains("\nsleeper.table.schema=" + schema + "\n");
        }

        @Test
        void shouldPrintUnsetPropertyValue() throws Exception {
            // When / Then
            assertThat(printEmptyInstanceProperties())
                    .contains("# (no value set, uncomment to set a value)\n" +
                            "# sleeper.logging.root.level=\n");
        }

        @Test
        void shouldPrintDefaultedPropertyValue() throws Exception {
            // When / Then
            assertThat(printEmptyInstanceProperties())
                    .contains("# (using default value shown below, uncomment to set a value)\n" +
                            "# sleeper.retain.infra.after.destroy=true\n");
        }

        @Test
        void shouldPrintPropertyValueSetToEmptyString() throws Exception {
            // When / Then
            assertThat(printInstanceProperties("sleeper.logging.root.level="))
                    .contains("\nsleeper.logging.root.level=\n");
        }

        @Test
        void shouldPrintSpacingBetweenProperties() throws Exception {
            // When / Then
            assertThat(printInstanceProperties("" +
                    "sleeper.logging.parquet.level=INFO\n" +
                    "sleeper.logging.aws.level=INFO\n" +
                    "sleeper.logging.root.level=INFO"))
                    .contains("# The logging level for Parquet logs.\n" +
                            "sleeper.logging.parquet.level=INFO\n" +
                            "\n" +
                            "# The logging level for AWS logs.\n" +
                            "sleeper.logging.aws.level=INFO\n" +
                            "\n" +
                            "# The logging level for everything else.\n" +
                            "sleeper.logging.root.level=INFO");
        }

        @Test
        void shouldPrintPropertiesNotKnownBySleeper() throws IOException {
            assertThat(printInstanceProperties("unknown.property=test"))
                    .contains("\n\n" +
                            "# The following properties are not recognised by Sleeper.\n" +
                            "unknown.property=test\n");
        }

        @Test
        void shouldNotPrintPropertiesNotKnownBySleeperWhenNoneSet() throws IOException {
            assertThat(printEmptyInstanceProperties())
                    .doesNotContain("The following properties are not recognised by Sleeper.");
        }

        @Test
        void shouldSortPropertiesNotKnownBySleeper() throws IOException {
            assertThat(printInstanceProperties("" +
                    "unknown.property.2=test\n" +
                    "unknown.property.1=test\n" +
                    "unknown.property.3=test\n"))
                    .contains("\n\n" +
                            "# The following properties are not recognised by Sleeper.\n" +
                            "unknown.property.1=test\n" +
                            "unknown.property.2=test\n" +
                            "unknown.property.3=test\n");
        }

        @Test
        void shouldEscapeSpecialCharactersInPropertyKey() throws IOException {
            InstanceProperties instanceProperties = new InstanceProperties(loadProperties("" +
                    "unknown\\=property=test"));
            assertThat(printInstanceProperties(instanceProperties))
                    .contains("\n\n" +
                            "# The following properties are not recognised by Sleeper.\n" +
                            "unknown\\=property=test\n");
            assertThat(instanceProperties.getUnknownProperties())
                    .containsExactly(Map.entry("unknown=property", "test"));
        }

        @Test
        void shouldEscapeSpecialCharactersInSchemaPropertyValue() {
            // Given
            String schemaWithNewlines = "{\"rowKeyFields\":[{\\n" +
                    "\"name\":\"key\",\"type\":\"LongType\"\\n" +
                    "}],\\n" +
                    "\"sortKeyFields\":[],\\n" +
                    "\"valueFields\":[]}";
            TableProperties tableProperties = createTablePropertiesWithSchemaInString("" +
                    "sleeper.table.schema=" + schemaWithNewlines);
            // When / Then
            assertThat(printTableProperties(tableProperties))
                    .contains("\nsleeper.table.schema=" + schemaWithNewlines + "\n");
            assertThat(tableProperties.getSchema()).isEqualTo(Schema.builder()
                    .rowKeyFields(new Field("key", new LongType()))
                    .build());
            assertThat(tableProperties.get(SCHEMA)).isEqualTo("{\"rowKeyFields\":[{\n" +
                    "\"name\":\"key\",\"type\":\"LongType\"\n" +
                    "}],\n" +
                    "\"sortKeyFields\":[],\n" +
                    "\"valueFields\":[]}");
        }

        @Test
        void shouldEscapeSpecialCharactersInPropertyValueForUnknownProperty() throws IOException {
            InstanceProperties instanceProperties = new InstanceProperties(loadProperties("" +
                    "multiline.property=one\\ntwo\\nthree"));
            assertThat(printInstanceProperties("multiline.property=one\\ntwo\\nthree"))
                    .contains("\n\n" +
                            "# The following properties are not recognised by Sleeper.\n" +
                            "multiline.property=one\\ntwo\\nthree\n");
            assertThat(instanceProperties.getUnknownProperties())
                    .containsExactly(Map.entry("multiline.property", "one\ntwo\nthree"));
        }
    }

    @Nested
    @DisplayName("Print groups")
    class PrintGroups {
        @Test
        void shouldPrintPropertyGroupDescriptions() throws IOException {
            // When / Then
            assertThat(printEmptyInstanceProperties())
                    .contains("# The following properties are commonly used throughout Sleeper\n\n")
                    .contains("# The following properties relate to standard ingest\n\n")
                    .contains("# The following properties relate to bulk import, i.e. ingesting data using Spark jobs running on EMR\n" +
                            "# or EKS.\n\n")
                    .contains("# The following properties relate to the splitting of partitions\n\n")
                    .contains("# The following properties relate to compactions.\n\n")
                    .contains("# The following properties relate to queries.\n\n");
        }

        @Test
        void shouldPrintPropertyGroupsInTheCorrectOrder() throws IOException {
            // When
            String output = printEmptyInstanceProperties();

            // Then
            assertThat(output.indexOf("The following properties relate to standard ingest"))
                    .isLessThan(output.indexOf("The following properties relate to bulk import"));
            assertThat(output.indexOf("The following properties relate to garbage collection"))
                    .isLessThan(output.indexOf("The following properties relate to compactions"));
            assertThat(output.indexOf("The following properties relate to compactions"))
                    .isLessThan(output.indexOf("The following properties relate to queries"));
        }

        @Test
        void shouldDisplayPropertiesInTheCorrectGroup() throws IOException {
            // When
            String output = printEmptyInstanceProperties();

            // Then check that one UserDefinedInstanceProperty is in the correct group
            assertThat(output.indexOf("sleeper.id"))
                    .isBetween(
                            output.indexOf("The following properties are commonly used throughout Sleeper"),
                            output.indexOf("The following properties relate to standard ingest"));
            // Then check that one SystemDefinedInstanceProperty is in the correct group
            assertThat(output.indexOf("sleeper.config.bucket"))
                    .isBetween(
                            output.indexOf("The following properties are commonly used throughout Sleeper"),
                            output.indexOf("The following properties relate to standard ingest"));
        }
    }

    private static String printEmptyInstanceProperties() throws IOException {
        return printInstanceProperties("");
    }

    private static String printInstanceProperties(String properties) throws IOException {
        return printInstanceProperties(new InstanceProperties(loadProperties(properties)));
    }

    private static String printInstanceProperties(InstanceProperties properties) {
        return print(SleeperPropertiesPrettyPrinter::forInstanceProperties, properties);
    }

    private static String printTableProperties(Schema schema) {
        TableProperties tableProperties = createTestTableProperties(new InstanceProperties(), schema);
        return printTableProperties(tableProperties);
    }

    private static TableProperties createTablePropertiesWithSchemaInString(String properties) {
        TableProperties tableProperties = createTestTablePropertiesWithNoSchema(new InstanceProperties());
        try {
            tableProperties.loadFromString(properties);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return tableProperties;
    }

    private static String printTableProperties(TableProperties tableProperties) {
        return print(SleeperPropertiesPrettyPrinter::forTableProperties, tableProperties);
    }

    private static <T extends SleeperProperty> String print(
            Function<PrintWriter, SleeperPropertiesPrettyPrinter<T>> printer, SleeperProperties<T> values) {
        // Test against PrintStream as the clients module builds its writer from that.
        // This forces us to ensure the output is flushed to the console before the system continues.
        ToStringPrintStream out = new ToStringPrintStream();
        printer.apply(out.getPrintWriter()).print(values);
        return out.toString();
    }
}