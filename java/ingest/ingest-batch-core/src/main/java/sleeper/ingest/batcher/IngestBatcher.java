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

package sleeper.ingest.batcher;

import org.apache.commons.lang3.EnumUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.configuration.properties.table.TablePropertiesProvider;
import sleeper.configuration.properties.validation.BatchIngestMode;
import sleeper.ingest.job.IngestJob;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static sleeper.configuration.properties.SystemDefinedInstanceProperty.BULK_IMPORT_EMR_JOB_QUEUE_URL;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.INGEST_JOB_QUEUE_URL;
import static sleeper.configuration.properties.table.TableProperty.INGEST_BATCHER_INGEST_MODE;
import static sleeper.configuration.properties.table.TableProperty.INGEST_BATCHER_MAX_FILE_AGE_SECONDS;
import static sleeper.configuration.properties.table.TableProperty.INGEST_BATCHER_MAX_JOB_FILES;
import static sleeper.configuration.properties.table.TableProperty.INGEST_BATCHER_MAX_JOB_SIZE;
import static sleeper.configuration.properties.table.TableProperty.INGEST_BATCHER_MIN_JOB_FILES;
import static sleeper.configuration.properties.table.TableProperty.INGEST_BATCHER_MIN_JOB_SIZE;
import static sleeper.configuration.properties.table.TableProperty.TABLE_NAME;

public class IngestBatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestBatcher.class);
    private final InstanceProperties instanceProperties;
    private final TablePropertiesProvider tablePropertiesProvider;
    private final Supplier<String> jobIdSupplier;
    private final Supplier<Instant> timeSupplier;
    private final IngestBatcherStateStore store;
    private final IngestBatcherQueueClient queueClient;

    private IngestBatcher(Builder builder) {
        instanceProperties = Objects.requireNonNull(builder.instanceProperties, "instanceProperties must not be null");
        tablePropertiesProvider = Objects.requireNonNull(builder.tablePropertiesProvider, "tablePropertiesProvider must not be null");
        jobIdSupplier = Objects.requireNonNull(builder.jobIdSupplier, "jobIdSupplier must not be null");
        timeSupplier = Objects.requireNonNull(builder.timeSupplier, "timeSupplier must not be null");
        store = Objects.requireNonNull(builder.store, "store must not be null");
        queueClient = Objects.requireNonNull(builder.queueClient, "queueClient must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public void batchFiles() {
        Instant time = timeSupplier.get();
        store.getPendingFilesOldestFirst().stream()
                .collect(Collectors.groupingBy(FileIngestRequest::getTableName))
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> batchTableFiles(entry.getKey(), entry.getValue(), time));
    }

    private void batchTableFiles(String tableName, List<FileIngestRequest> inputFiles, Instant time) {
        TableProperties properties = tablePropertiesProvider.getTableProperties(tableName);
        int minFiles = properties.getInt(INGEST_BATCHER_MIN_JOB_FILES);
        long minBytes = properties.getBytes(INGEST_BATCHER_MIN_JOB_SIZE);
        Instant maxReceivedTime = time.minus(Duration.ofSeconds(
                properties.getInt(INGEST_BATCHER_MAX_FILE_AGE_SECONDS)));
        if ((inputFiles.size() >= minFiles &&
                totalBytes(inputFiles) >= minBytes)
                || inputFiles.stream().anyMatch(file -> file.getReceivedTime().isBefore(maxReceivedTime))) {
            createBatches(properties, inputFiles)
                    .forEach(batch -> sendBatch(properties, batch));
        }
    }

    private void sendBatch(TableProperties properties, List<FileIngestRequest> batch) {
        IngestJob job = IngestJob.builder()
                .id(jobIdSupplier.get())
                .tableName(properties.get(TABLE_NAME))
                .files(batch.stream()
                        .map(FileIngestRequest::getPathToFile)
                        .collect(Collectors.toList()))
                .build();
        try {
            store.assignJob(job.getId(), batch);
            queueClient.send(jobQueueUrl(properties), job);
        } catch (RuntimeException e) {
            LOGGER.error("Failed assigning/sending job: {}", job, e);
        }
    }

    private String jobQueueUrl(TableProperties properties) {
        String mode = properties.get(INGEST_BATCHER_INGEST_MODE);
        switch (EnumUtils.getEnumIgnoreCase(BatchIngestMode.class, mode)) {
            case STANDARD_INGEST:
                return instanceProperties.get(INGEST_JOB_QUEUE_URL);
            case BULK_IMPORT_EMR:
            case BULK_IMPORT_PERSISTENT_EMR:
            case BULK_IMPORT_EKS:
                return instanceProperties.get(BULK_IMPORT_EMR_JOB_QUEUE_URL);
            default:
                throw new IllegalArgumentException("Unrecognised ingest mode: " + mode);
        }
    }

    private static Stream<List<FileIngestRequest>> createBatches(
            TableProperties properties, List<FileIngestRequest> inputFiles) {
        BatchCreator batchCreator = new BatchCreator(properties);
        inputFiles.forEach(batchCreator::add);
        return batchCreator.streamBatches();
    }

    private static class BatchCreator {
        private final int maxFiles;
        private final long maxBytes;
        private final List<Batch> batches = new ArrayList<>();

        BatchCreator(TableProperties properties) {
            maxFiles = properties.getInt(INGEST_BATCHER_MAX_JOB_FILES);
            maxBytes = properties.getBytes(INGEST_BATCHER_MAX_JOB_SIZE);
        }

        void add(FileIngestRequest file) {
            getBatchWithSpaceFor(file).add(file);
        }

        Batch getBatchWithSpaceFor(FileIngestRequest file) {
            return batches.stream()
                    .filter(batch -> batch.hasSpaceForFile(file))
                    .findFirst().orElseGet(() -> {
                        Batch batch = new Batch(maxFiles, maxBytes);
                        batches.add(batch);
                        return batch;
                    });
        }

        Stream<List<FileIngestRequest>> streamBatches() {
            return batches.stream().map(Batch::getFiles);
        }
    }

    private static class Batch {
        private final List<FileIngestRequest> files = new ArrayList<>();
        private final int maxBatchSizeInFiles;
        private long batchSpaceInBytes;

        Batch(int maxBatchSizeInFiles, long maxBatchSizeInBytes) {
            this.maxBatchSizeInFiles = maxBatchSizeInFiles;
            this.batchSpaceInBytes = maxBatchSizeInBytes;
        }

        boolean hasSpaceForFile(FileIngestRequest file) {
            return file.getFileSizeBytes() <= batchSpaceInBytes
                    && files.size() < maxBatchSizeInFiles;
        }

        void add(FileIngestRequest file) {
            files.add(file);
            batchSpaceInBytes -= file.getFileSizeBytes();
        }

        List<FileIngestRequest> getFiles() {
            return files;
        }
    }

    private static long totalBytes(List<FileIngestRequest> files) {
        return files.stream().mapToLong(FileIngestRequest::getFileSizeBytes).sum();
    }

    public static final class Builder {
        private InstanceProperties instanceProperties;
        private TablePropertiesProvider tablePropertiesProvider;
        private Supplier<String> jobIdSupplier = () -> UUID.randomUUID().toString();
        private Supplier<Instant> timeSupplier = Instant::now;
        private IngestBatcherStateStore store;
        private IngestBatcherQueueClient queueClient;

        private Builder() {
        }

        public Builder instanceProperties(InstanceProperties instanceProperties) {
            this.instanceProperties = instanceProperties;
            return this;
        }

        public Builder tablePropertiesProvider(TablePropertiesProvider tablePropertiesProvider) {
            this.tablePropertiesProvider = tablePropertiesProvider;
            return this;
        }

        public Builder jobIdSupplier(Supplier<String> jobIdSupplier) {
            this.jobIdSupplier = jobIdSupplier;
            return this;
        }

        public Builder timeSupplier(Supplier<Instant> timeSupplier) {
            this.timeSupplier = timeSupplier;
            return this;
        }

        public Builder store(IngestBatcherStateStore store) {
            this.store = store;
            return this;
        }

        public Builder queueClient(IngestBatcherQueueClient queueClient) {
            this.queueClient = queueClient;
            return this;
        }

        public IngestBatcher build() {
            return new IngestBatcher(this);
        }
    }
}
