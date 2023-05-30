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
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.hadoop.ParquetWriter;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import sleeper.clients.deploy.DeployNewInstance;
import sleeper.clients.util.cdk.InvokeCdkForInstance;
import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.core.record.Record;
import sleeper.io.parquet.record.ParquetRecordWriterFactory;
import sleeper.systemtest.ingest.RandomRecordSupplier;

import java.io.IOException;
import java.nio.file.Path;

import static sleeper.configuration.properties.UserDefinedInstanceProperty.INGEST_SOURCE_BUCKET;

public class SystemTestForIngestBatcher {
    private final Path scriptsDir;
    private final Path propertiesTemplate;
    private final String instanceId;
    private final String vpc;
    private final String subnet;
    private final AmazonS3 s3ClientV1;
    private final S3Client s3ClientV2;
    private final CloudFormationClient cloudFormationClient;

    private SystemTestForIngestBatcher(Builder builder) {
        scriptsDir = builder.scriptsDir;
        propertiesTemplate = builder.propertiesTemplate;
        instanceId = builder.instanceId;
        vpc = builder.vpc;
        subnet = builder.subnet;
        s3ClientV1 = builder.s3ClientV1;
        s3ClientV2 = builder.s3ClientV2;
        cloudFormationClient = builder.cloudFormationClient;
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
        TableProperties tableProperties = new TableProperties(instanceProperties);
        tableProperties.loadFromS3(s3ClientV1, "system-test");

        writeFileWithRecords(tableProperties, sourceBucketName + "/file-1.parquet", 100);
        writeFileWithRecords(tableProperties, sourceBucketName + "/file-2.parquet", 100);
        writeFileWithRecords(tableProperties, sourceBucketName + "/file-3.parquet", 100);
        writeFileWithRecords(tableProperties, sourceBucketName + "/file-4.parquet", 100);
    }

    private void createSourceBucketIfMissing(String sourceBucketName) {
        try {
            s3ClientV2.headBucket(builder -> builder.bucket(sourceBucketName));
        } catch (NoSuchBucketException e) {
            s3ClientV2.createBucket(builder -> builder.bucket(sourceBucketName));
        }
    }

    private void createInstanceIfMissing(String sourceBucketName) throws IOException, InterruptedException {
        try {
            cloudFormationClient.describeStacks(builder -> builder.stackName(instanceId));
        } catch (CloudFormationException e) {
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

    public static final class Builder {
        private Path scriptsDir;
        private Path propertiesTemplate;
        private String instanceId;
        private String vpc;
        private String subnet;
        private AmazonS3 s3ClientV1;
        private S3Client s3ClientV2;
        private CloudFormationClient cloudFormationClient;

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

        public SystemTestForIngestBatcher build() {
            return new SystemTestForIngestBatcher(this);
        }
    }
}
