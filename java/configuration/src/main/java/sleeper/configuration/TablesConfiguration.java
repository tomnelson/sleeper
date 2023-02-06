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

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.core.schema.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static sleeper.configuration.properties.SleeperProperties.loadProperties;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.CONFIG_BUCKET;
import static sleeper.configuration.properties.table.TableProperty.TABLE_NAME;

public class TablesConfiguration {
    private final List<TableProperties> tables;

    private TablesConfiguration(List<TableProperties> tables) {
        this.tables = tables;
    }

    public static TablesConfiguration loadFromS3(AmazonS3 s3, InstanceProperties instanceProperties) {
        return new TablesConfiguration(
                loadTablesFromS3(s3, instanceProperties).collect(Collectors.toList()));
    }

    public static TablesConfiguration loadFromPath(Path path, InstanceProperties instanceProperties) {
        return new TablesConfiguration(
                loadTablesFromPath(instanceProperties, path).collect(Collectors.toList()));
    }

    public static TablesConfiguration fromTables(TableProperties... tables) {
        return new TablesConfiguration(List.of(tables));
    }

    public void saveToS3(AmazonS3 s3Client) {
        tables.forEach(table -> {
            try {
                table.saveToS3(s3Client);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void saveToPath(Path instancePropertiesFile) {
        Path baseDir = directoryOf(instancePropertiesFile);
        tables.forEach(table -> {
            if (table.getSchema() == null) {
                throw new IllegalArgumentException("Table " + table.get(TABLE_NAME) + " has no schema");
            }
            try {
                Path tableDir = Files.createDirectories(baseDir.resolve("tables/" + table.get(TABLE_NAME)));
                table.save(tableDir.resolve("table.properties"));

                table.getSchema().save(tableDir.resolve("schema.json"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }


    public List<TableProperties> getTables() {
        return tables;
    }

    private static Stream<TableProperties> loadTablesFromS3(AmazonS3 s3, InstanceProperties instanceProperties) {
        String configBucket = instanceProperties.get(CONFIG_BUCKET);
        return s3.listObjectsV2(configBucket, "tables/")
                .getObjectSummaries().stream()
                .map(tableConfigObject -> loadTableFromS3(s3, instanceProperties, tableConfigObject));
    }

    private static TableProperties loadTableFromS3(
            AmazonS3 s3, InstanceProperties instanceProperties, S3ObjectSummary tableConfigObject) {
        TableProperties tableProperties = new TableProperties(instanceProperties);
        try (InputStream in = s3.getObject(
                        tableConfigObject.getBucketName(),
                        tableConfigObject.getKey())
                .getObjectContent()) {
            tableProperties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return tableProperties;
    }

    public static Stream<TableProperties> loadTablesFromPath(
            InstanceProperties instanceProperties, Path instancePropertiesFile) {
        Path baseDir = directoryOf(instancePropertiesFile);
        return streamBaseAndTableFolders(baseDir)
                .map(folder -> readTablePropertiesFolderOrNull(instanceProperties, folder))
                .filter(Objects::nonNull);
    }

    private static TableProperties readTablePropertiesFolderOrNull(
            InstanceProperties instanceProperties, Path folder) {
        Path propertiesPath = folder.resolve("table.properties");
        Path schemaPath = folder.resolve("schema.json");
        if (!Files.exists(propertiesPath)) {
            return null;
        }
        try {
            Properties properties = loadProperties(propertiesPath);
            if (Files.exists(schemaPath)) {
                Schema schema = Schema.load(schemaPath);
                return new TableProperties(instanceProperties, schema, properties);
            }
            return new TableProperties(instanceProperties, properties);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Path directoryOf(Path filePath) {
        Path parent = filePath.getParent();
        if (parent == null) {
            return Paths.get(".");
        } else {
            return parent;
        }
    }

    private static Stream<Path> streamBaseAndTableFolders(Path baseDir) {
        return Stream.concat(
                Stream.of(baseDir),
                streamTableFolders(baseDir));
    }

    private static Stream<Path> streamTableFolders(Path baseDir) {
        Path tablesFolder = baseDir.resolve("tables");
        if (!Files.isDirectory(tablesFolder)) {
            return Stream.empty();
        }
        List<Path> tables;
        try (Stream<Path> pathStream = Files.list(tablesFolder)) {
            tables = pathStream
                    .filter(Files::isDirectory)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list table configuration directories", e);
        }
        return tables.stream().sorted();
    }
}
