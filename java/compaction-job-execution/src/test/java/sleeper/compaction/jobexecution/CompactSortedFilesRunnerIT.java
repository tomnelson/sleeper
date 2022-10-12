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
package sleeper.compaction.jobexecution;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import org.apache.hadoop.fs.Path;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import sleeper.compaction.job.CompactionJob;
import sleeper.compaction.job.CompactionJobSerDe;
import sleeper.compaction.job.CompactionJobStatusStore;
import sleeper.compaction.status.job.DynamoDBCompactionJobStatusStore;
import sleeper.compaction.status.job.DynamoDBCompactionJobStatusStoreCreator;
import sleeper.compaction.status.task.DynamoDBCompactionTaskStatusStore;
import sleeper.compaction.status.task.DynamoDBCompactionTaskStatusStoreCreator;
import sleeper.compaction.task.CompactionTaskStatusStore;
import sleeper.configuration.jars.ObjectFactory;
import sleeper.configuration.jars.ObjectFactoryException;
import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.configuration.properties.table.TablePropertiesProvider;
import sleeper.core.CommonTestConstants;
import sleeper.core.key.Key;
import sleeper.core.record.Record;
import sleeper.core.schema.Field;
import sleeper.core.schema.Schema;
import sleeper.core.schema.type.LongType;
import sleeper.io.parquet.record.ParquetRecordWriter;
import sleeper.io.parquet.record.SchemaConverter;
import sleeper.job.common.action.ActionException;
import sleeper.statestore.FileInfo;
import sleeper.statestore.StateStore;
import sleeper.statestore.StateStoreException;
import sleeper.statestore.StateStoreProvider;
import sleeper.table.job.TableCreator;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.COMPACTION_JOB_QUEUE_URL;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.CONFIG_BUCKET;
import static sleeper.configuration.properties.UserDefinedInstanceProperty.FILE_SYSTEM;
import static sleeper.configuration.properties.UserDefinedInstanceProperty.ID;
import static sleeper.configuration.properties.table.TableProperty.COMPACTION_FILES_BATCH_SIZE;
import static sleeper.configuration.properties.table.TableProperty.TABLE_NAME;

public class CompactSortedFilesRunnerIT {

    @ClassRule
    public static LocalStackContainer localStackContainer = new LocalStackContainer(DockerImageName.parse(CommonTestConstants.LOCALSTACK_DOCKER_IMAGE)).withServices(
            LocalStackContainer.Service.S3, LocalStackContainer.Service.SQS, LocalStackContainer.Service.DYNAMODB
    );

    private AmazonS3 createS3Client() {
        return AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(localStackContainer.getEndpointConfiguration(LocalStackContainer.Service.S3))
                .withCredentials(localStackContainer.getDefaultCredentialsProvider())
                .build();
    }

    private AmazonSQS createSQSClient() {
        return AmazonSQSClientBuilder.standard()
                .withEndpointConfiguration(localStackContainer.getEndpointConfiguration(LocalStackContainer.Service.SQS))
                .withCredentials(localStackContainer.getDefaultCredentialsProvider())
                .build();
    }

    private AmazonDynamoDB createDynamoClient() {
        return AmazonDynamoDBClientBuilder.standard()
                .withCredentials(localStackContainer.getDefaultCredentialsProvider())
                .withEndpointConfiguration(localStackContainer.getEndpointConfiguration(LocalStackContainer.Service.DYNAMODB))
                .build();
    }

    private Schema createSchema() {
        return Schema.builder()
                .rowKeyFields(new Field("key", new LongType()))
                .valueFields(new Field("value1", new LongType()), new Field("value2", new LongType()))
                .build();
    }

    private InstanceProperties createProperties(AmazonS3 s3) {
        AmazonSQS sqs = createSQSClient();
        String queue = UUID.randomUUID().toString();
        String queueUrl = sqs.createQueue(queue).getQueueUrl();
        sqs.shutdown();

        InstanceProperties instanceProperties = new InstanceProperties();
        instanceProperties.set(ID, UUID.randomUUID().toString());
        instanceProperties.set(CONFIG_BUCKET, UUID.randomUUID().toString());
        instanceProperties.set(COMPACTION_JOB_QUEUE_URL, queueUrl);
        instanceProperties.set(FILE_SYSTEM, "");

        s3.createBucket(instanceProperties.get(CONFIG_BUCKET));

        return instanceProperties;
    }

    private TableProperties createTable(AmazonS3 s3, AmazonDynamoDB dynamoDB, InstanceProperties instanceProperties, String tableName, Schema schema) throws IOException, StateStoreException {
        TableProperties tableProperties = new TableProperties(instanceProperties);
        tableProperties.set(TABLE_NAME, tableName);
        tableProperties.setSchema(schema);
        tableProperties.set(COMPACTION_FILES_BATCH_SIZE, "5");
        TableCreator tableCreator = new TableCreator(s3, dynamoDB, instanceProperties);
        tableCreator.createTable(tableProperties);

        tableProperties.loadFromS3(s3, tableName);
        return tableProperties;
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder(CommonTestConstants.TMP_DIRECTORY);

    @Test
    public void shouldDeleteMessages() throws IOException, StateStoreException, ObjectFactoryException, InterruptedException, ActionException {
        // Given
        //  - Clients
        AmazonS3 s3 = createS3Client();
        AmazonDynamoDB dynamoDB = createDynamoClient();
        AmazonSQS sqsClient = createSQSClient();
        //  - Schema
        Schema schema = createSchema();
        //  - Create table and state store
        String tableName = UUID.randomUUID().toString();
        InstanceProperties instanceProperties = createProperties(s3);
        TableProperties tableProperties = createTable(s3, dynamoDB, instanceProperties, tableName, schema);
        StateStoreProvider stateStoreProvider = new StateStoreProvider(dynamoDB, instanceProperties);
        TablePropertiesProvider tablePropertiesProvider = new TablePropertiesProvider(s3, instanceProperties);
        StateStore stateStore = stateStoreProvider.getStateStore(tableProperties);
        stateStore.initialise();
        DynamoDBCompactionJobStatusStoreCreator.create(instanceProperties, dynamoDB);
        CompactionJobStatusStore jobStatusStore = DynamoDBCompactionJobStatusStore.from(dynamoDB, instanceProperties);
        DynamoDBCompactionTaskStatusStoreCreator.create(instanceProperties, dynamoDB);
        CompactionTaskStatusStore taskStatusStore = DynamoDBCompactionTaskStatusStore.from(dynamoDB, instanceProperties);
        //  - Create four files of sorted data
        String folderName = folder.newFolder().getAbsolutePath();
        String file1 = folderName + "/file1.parquet";
        String file2 = folderName + "/file2.parquet";
        String file3 = folderName + "/file3.parquet";
        String file4 = folderName + "/file4.parquet";
        FileInfo fileInfo1 = FileInfo.builder()
                .rowKeyTypes(new LongType())
                .filename(file1)
                .fileStatus(FileInfo.FileStatus.ACTIVE)
                .partitionId("1")
                .numberOfRecords(100L)
                .minRowKey(Key.create(0L))
                .maxRowKey(Key.create(198L))
                .build();
        FileInfo fileInfo2 = FileInfo.builder()
                .rowKeyTypes(new LongType())
                .filename(file2)
                .fileStatus(FileInfo.FileStatus.ACTIVE)
                .partitionId("1")
                .numberOfRecords(100L)
                .minRowKey(Key.create(1L))
                .maxRowKey(Key.create(199L))
                .build();
        FileInfo fileInfo3 = FileInfo.builder()
                .rowKeyTypes(new LongType())
                .filename(file3)
                .fileStatus(FileInfo.FileStatus.ACTIVE)
                .partitionId("1")
                .numberOfRecords(100L)
                .minRowKey(Key.create(0L))
                .maxRowKey(Key.create(198L))
                .build();
        FileInfo fileInfo4 = FileInfo.builder()
                .rowKeyTypes(new LongType())
                .filename(file4)
                .fileStatus(FileInfo.FileStatus.ACTIVE)
                .partitionId("1")
                .numberOfRecords(100L)
                .minRowKey(Key.create(1L))
                .maxRowKey(Key.create(199L))
                .build();
        ParquetRecordWriter writer1 = new ParquetRecordWriter(new Path(file1), SchemaConverter.getSchema(schema), schema);
        for (int i = 0; i < 100; i++) {
            Record record = new Record();
            record.put("key", (long) 2 * i);
            record.put("value1", (long) 2 * i);
            record.put("value2", 987654321L);
            writer1.write(record);
        }
        writer1.close();
        ParquetRecordWriter writer2 = new ParquetRecordWriter(new Path(file2), SchemaConverter.getSchema(schema), schema);
        for (int i = 0; i < 100; i++) {
            Record record = new Record();
            record.put("key", (long) 2 * i + 1);
            record.put("value1", 1001L);
            record.put("value2", 123456789L);
            writer2.write(record);
        }
        writer2.close();
        ParquetRecordWriter writer3 = new ParquetRecordWriter(new Path(file3), SchemaConverter.getSchema(schema), schema);
        for (int i = 0; i < 100; i++) {
            Record record = new Record();
            record.put("key", (long) 2 * i);
            record.put("value1", (long) 2 * i);
            record.put("value2", 987654321L);
            writer3.write(record);
        }
        writer3.close();
        ParquetRecordWriter writer4 = new ParquetRecordWriter(new Path(file4), SchemaConverter.getSchema(schema), schema);
        for (int i = 0; i < 100; i++) {
            Record record = new Record();
            record.put("key", (long) 2 * i + 1);
            record.put("value1", 1001L);
            record.put("value2", 123456789L);
            writer4.write(record);
        }
        writer4.close();
        //  - Update Dynamo state store with details of files
        stateStore.addFiles(Arrays.asList(fileInfo1, fileInfo2, fileInfo3, fileInfo4));
        //  - Create two compaction jobs and put on queue
        CompactionJob compactionJob1 = CompactionJob.builder()
                .tableName(tableName)
                .jobId("job1")
                .partitionId("root")
                .dimension(0)
                .inputFiles(Arrays.asList(file1, file2))
                .isSplittingJob(false)
                .outputFile(folderName + "/output1.parquet").build();
        CompactionJob compactionJob2 = CompactionJob.builder()
                .tableName(tableName)
                .jobId("job2")
                .partitionId("root")
                .dimension(0)
                .inputFiles(Arrays.asList(file3, file4))
                .isSplittingJob(false)
                .outputFile(folderName + "/output2.parquet").build();
        CompactionJobSerDe jobSerDe = new CompactionJobSerDe(tablePropertiesProvider);
        String job1Json = jobSerDe.serialiseToString(compactionJob1);
        String job2Json = jobSerDe.serialiseToString(compactionJob2);
        SendMessageRequest sendMessageRequest = new SendMessageRequest()
                .withQueueUrl(instanceProperties.get(COMPACTION_JOB_QUEUE_URL))
                .withMessageBody(job1Json);
        sqsClient.sendMessage(sendMessageRequest);
        sendMessageRequest = new SendMessageRequest()
                .withQueueUrl(instanceProperties.get(COMPACTION_JOB_QUEUE_URL))
                .withMessageBody(job2Json);
        sqsClient.sendMessage(sendMessageRequest);

        // When
        CompactSortedFilesRunner runner = new CompactSortedFilesRunner(
                instanceProperties, ObjectFactory.noUserJars(),
                tablePropertiesProvider, stateStoreProvider, jobStatusStore, taskStatusStore,
                "task-id", instanceProperties.get(COMPACTION_JOB_QUEUE_URL), sqsClient,
                1, 5);
        runner.run();

        // Then
        //  - There should be no messages left on the queue
        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest()
                .withQueueUrl(instanceProperties.get(COMPACTION_JOB_QUEUE_URL))
                .withWaitTimeSeconds(2);
        ReceiveMessageResult result = sqsClient.receiveMessage(receiveMessageRequest);
        assertThat(result.getMessages()).isEmpty();
        // - Check DynamoDBStateStore has correct active files
        List<FileInfo> activeFiles = stateStoreProvider.getStateStore(tableName, tablePropertiesProvider).getActiveFiles();
        assertThat(activeFiles)
                .extracting(FileInfo::getFilename)
                .containsExactlyInAnyOrder(compactionJob1.getOutputFile(), compactionJob2.getOutputFile());
    }
}
