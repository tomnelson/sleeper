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

package sleeper.systemtest.ingest.batcher;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.google.gson.Gson;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.hadoop.ParquetWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

import sleeper.clients.deploy.DeployNewInstance;
import sleeper.clients.deploy.InvokeLambda;
import sleeper.clients.util.GsonConfig;
import sleeper.clients.util.PollWithRetries;
import sleeper.clients.util.cdk.InvokeCdkForInstance;
import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.InstanceProperty;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.configuration.properties.validation.BatchIngestMode;
import sleeper.core.record.Record;
import sleeper.ingest.batcher.submitter.FileIngestRequestSerDe;
import sleeper.io.parquet.record.ParquetRecordWriterFactory;
import sleeper.job.common.QueueMessageCount;
import sleeper.systemtest.ingest.RandomRecordSupplier;
import sleeper.systemtest.util.WaitForQueueEstimate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static sleeper.configuration.properties.SystemDefinedInstanceProperty.BULK_IMPORT_EMR_JOB_QUEUE_URL;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.INGEST_BATCHER_JOB_CREATION_FUNCTION;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.INGEST_BATCHER_SUBMIT_QUEUE_URL;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.INGEST_JOB_QUEUE_URL;
import static sleeper.configuration.properties.UserDefinedInstanceProperty.INGEST_SOURCE_BUCKET;
import static sleeper.configuration.properties.table.TableProperty.INGEST_BATCHER_INGEST_MODE;

public class SystemTestForIngestBatcher {
    private static final Gson GSON = GsonConfig.standardBuilder().create();
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemTestForIngestBatcher.class);
    private static final long JOBS_ESTIMATE_POLL_INTERVAL_MILLIS = 5000;
    private static final int JOBS_ESTIMATE_MAX_POLLS = 12;
    private final Path scriptsDir;
    private final Path propertiesTemplate;
    private final String instanceId;
    private final String vpc;
    private final String subnet;
    private final AmazonS3 s3ClientV1;
    private final S3Client s3ClientV2;
    private final CloudFormationClient cloudFormationClient;
    private final AmazonSQS sqsClient;
    private final LambdaClient lambdaClient;

    private SystemTestForIngestBatcher(Builder builder) {
        scriptsDir = builder.scriptsDir;
        propertiesTemplate = builder.propertiesTemplate;
        instanceId = builder.instanceId;
        vpc = builder.vpc;
        subnet = builder.subnet;
        s3ClientV1 = builder.s3ClientV1;
        s3ClientV2 = builder.s3ClientV2;
        cloudFormationClient = builder.cloudFormationClient;
        sqsClient = builder.sqsClient;
        lambdaClient = builder.lambdaClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 5) {
            throw new IllegalArgumentException("Usage: <scripts-dir> <properties-template> <instance-id> <vpc> <subnet>");
        }
        try (S3Client s3Client = S3Client.create();
             CloudFormationClient cloudFormationClient = CloudFormationClient.create()) {
            builder().scriptsDir(Path.of(args[0]))
                    .propertiesTemplate(Path.of(args[1]))
                    .instanceId(args[2])
                    .vpc(args[3])
                    .subnet(args[4])
                    .s3ClientV1(AmazonS3ClientBuilder.defaultClient())
                    .s3ClientV2(s3Client)
                    .cloudFormationClient(cloudFormationClient)
                    .sqsClient(AmazonSQSClientBuilder.defaultClient())
                    .lambdaClient(LambdaClient.create())
                    .build().run();
        }
    }

    /**
     * Create an S3 bucket
     * Add it as an ingest source bucket in a Sleeper instance
     * Deploy instance
     * Submit files to the ingest batcher
     * Trigger the ingest batcher to create jobs
     * Test ingest via both standard ingest and bulk import
     */
    public void run() throws IOException, InterruptedException {
        String sourceBucketName = "sleeper-" + instanceId + "-ingest-source";
        createSourceBucketIfMissing(sourceBucketName);
        createInstanceIfMissing(sourceBucketName);

        InstanceProperties instanceProperties = new InstanceProperties();
        instanceProperties.loadFromS3GivenInstanceId(s3ClientV1, instanceId);
        LOGGER.info("Testing ingest batcher mode: {}", BatchIngestMode.STANDARD_INGEST);
        TableProperties tableProperties = new TableProperties(instanceProperties);
        tableProperties.loadFromS3(s3ClientV1, "system-test");

        List<String> standardIngestFiles = List.of("file-1.parquet", "file-2.parquet", "file-3.parquet", "file-4.parquet");
        LOGGER.info("Writing test ingest files to {} for standard ingest test", sourceBucketName);
        for (String file : standardIngestFiles) {
            writeFileWithRecords(tableProperties, sourceBucketName + "/" + file, 100);
        }
        sendFilesAndTriggerJobCreation(instanceProperties, sourceBucketName, standardIngestFiles);

        waitForIngestJobQueue(instanceProperties, INGEST_JOB_QUEUE_URL);
        int standardIngestQueueMessageCount = getQueueMessageCount(instanceProperties.get(INGEST_JOB_QUEUE_URL));
        if (standardIngestQueueMessageCount != 2) {
            LOGGER.error("Some ingest jobs did not appear on the standard ingest job queue. Expected {}, got {}",
                    2, standardIngestQueueMessageCount);
            return;
        }
        LOGGER.info("Successfully batched files with ingest batcher mode: {}", BatchIngestMode.STANDARD_INGEST);

        LOGGER.info("Testing ingest batcher mode: {}", BatchIngestMode.BULK_IMPORT_EMR);
        tableProperties.set(INGEST_BATCHER_INGEST_MODE, BatchIngestMode.BULK_IMPORT_EMR.toString());
        tableProperties.saveToS3(s3ClientV1);

        List<String> bulkImportFiles = List.of("file-5.parquet", "file-6.parquet", "file-7.parquet", "file-8.parquet");
        LOGGER.info("Writing test ingest files to {} for bulk import test", sourceBucketName);
        for (String file : bulkImportFiles) {
            writeFileWithRecords(tableProperties, sourceBucketName + "/" + file, 100);
        }
        sendFilesAndTriggerJobCreation(instanceProperties, sourceBucketName, bulkImportFiles);

        waitForIngestJobQueue(instanceProperties, BULK_IMPORT_EMR_JOB_QUEUE_URL);
        int bulkImportEmrQueueMessageCount = getQueueMessageCount(instanceProperties.get(BULK_IMPORT_EMR_JOB_QUEUE_URL));
        if (bulkImportEmrQueueMessageCount != 2) {
            LOGGER.error("Some ingest jobs did not appear on the bulk import EMR job queue. Expected {}, got {}",
                    2, bulkImportEmrQueueMessageCount);
            return;
        }
        LOGGER.info("Successfully batched files with ingest batcher mode: {}", BatchIngestMode.BULK_IMPORT_EMR);
    }

    private void waitForIngestJobQueue(InstanceProperties properties, InstanceProperty queueProperty) throws InterruptedException {
        WaitForQueueEstimate.notEmpty(QueueMessageCount.withSqsClient(sqsClient), properties, queueProperty,
                        PollWithRetries.intervalAndMaxPolls(JOBS_ESTIMATE_POLL_INTERVAL_MILLIS, JOBS_ESTIMATE_MAX_POLLS))
                .pollUntilFinished();
    }

    private int getQueueMessageCount(String queueUrl) {
        return QueueMessageCount.withSqsClient(sqsClient)
                .getQueueMessageCount(queueUrl)
                .getApproximateNumberOfMessages();
    }

    private void sendFilesAndTriggerJobCreation(InstanceProperties properties, String sourceBucketName, List<String> files) {
        LOGGER.info("Sending files to ingest batcher queue");
        sendFilesToBatcherSubmitQueue(properties.get(INGEST_BATCHER_SUBMIT_QUEUE_URL), sourceBucketName, files);
        LOGGER.info("Triggering ingest batcher job creation lambda");
        InvokeLambda.invokeWith(lambdaClient, properties.get(INGEST_BATCHER_JOB_CREATION_FUNCTION));
    }

    private void createSourceBucketIfMissing(String sourceBucketName) {
        try {
            s3ClientV2.headBucket(builder -> builder.bucket(sourceBucketName));
            LOGGER.info("Bucket already exists, clearing bucket");
            List<ObjectIdentifier> objects = s3ClientV2.listObjectVersions(builder -> builder.bucket(sourceBucketName))
                    .versions().stream()
                    .map(obj -> ObjectIdentifier.builder().key(obj.key()).versionId(obj.versionId()).build())
                    .collect(Collectors.toList());
            s3ClientV2.deleteObjects(builder -> builder.bucket(sourceBucketName).delete(Delete.builder()
                    .objects(objects).build()));
        } catch (NoSuchBucketException e) {
            LOGGER.info("Creating bucket: " + sourceBucketName);
            s3ClientV2.createBucket(builder -> builder.bucket(sourceBucketName));
        }
    }

    private void createInstanceIfMissing(String sourceBucketName) throws IOException, InterruptedException {
        try {
            cloudFormationClient.describeStacks(builder -> builder.stackName(instanceId));
            LOGGER.info("Instance already exists: " + instanceId);
        } catch (CloudFormationException e) {
            LOGGER.info("Deploying instance: " + instanceId);
            DeployNewInstance.builder().scriptsDirectory(scriptsDir)
                    .instancePropertiesTemplate(propertiesTemplate)
                    .extraInstanceProperties(properties ->
                            properties.setProperty(INGEST_SOURCE_BUCKET.getPropertyName(), sourceBucketName))
                    .instanceId(instanceId)
                    .vpcId(vpc)
                    .subnetId(subnet)
                    .deployPaused(true)
                    .tableName("system-test")
                    .instanceType(InvokeCdkForInstance.Type.STANDARD)
                    .deployWithDefaultClients();
        }
    }

    private void writeFileWithRecords(TableProperties tableProperties, String filePath, int numRecords) throws IOException {
        try (ParquetWriter<Record> writer = ParquetRecordWriterFactory.createParquetRecordWriter(
                new org.apache.hadoop.fs.Path("s3a://" + filePath), tableProperties, new Configuration())) {
            RandomRecordSupplier supplier = new RandomRecordSupplier(tableProperties.getSchema());
            for (int i = 0; i < numRecords; i++) {
                writer.write(supplier.get());
            }
        }
    }

    private void sendFilesToBatcherSubmitQueue(String queueUrl, String sourceBucketName, List<String> files) {
        for (String file : files) {
            sqsClient.sendMessage(queueUrl, createFileIngestRequestMessage(sourceBucketName, file));
        }
    }

    private String createFileIngestRequestMessage(String sourceBucketName, String file) {
        long fileSizeBytes = s3ClientV2.headObject(builder -> builder.bucket(sourceBucketName).key(file)).contentLength();
        return GSON.toJson(new FileIngestRequestSerDe.Request(sourceBucketName + "/" + file, fileSizeBytes, "system-test"));
    }

    public static final class Builder {
        private Path scriptsDir;
        private Path propertiesTemplate;
        private String instanceId;
        private String vpc;
        private String subnet;
        private AmazonS3 s3ClientV1;
        private S3Client s3ClientV2;
        private CloudFormationClient cloudFormationClient;
        private AmazonSQS sqsClient;
        private LambdaClient lambdaClient;

        private Builder() {
        }

        public Builder scriptsDir(Path scriptsDir) {
            this.scriptsDir = scriptsDir;
            return this;
        }

        public Builder propertiesTemplate(Path propertiesTemplate) {
            this.propertiesTemplate = propertiesTemplate;
            return this;
        }

        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Builder vpc(String vpc) {
            this.vpc = vpc;
            return this;
        }

        public Builder subnet(String subnet) {
            this.subnet = subnet;
            return this;
        }

        public Builder s3ClientV1(AmazonS3 s3ClientV1) {
            this.s3ClientV1 = s3ClientV1;
            return this;
        }

        public Builder s3ClientV2(S3Client s3ClientV2) {
            this.s3ClientV2 = s3ClientV2;
            return this;
        }

        public Builder cloudFormationClient(CloudFormationClient cloudFormationClient) {
            this.cloudFormationClient = cloudFormationClient;
            return this;
        }

        public Builder sqsClient(AmazonSQS sqsClient) {
            this.sqsClient = sqsClient;
            return this;
        }

        public Builder lambdaClient(LambdaClient lambdaClient) {
            this.lambdaClient = lambdaClient;
            return this;
        }

        public SystemTestForIngestBatcher build() {
            return new SystemTestForIngestBatcher(this);
        }
    }
}
