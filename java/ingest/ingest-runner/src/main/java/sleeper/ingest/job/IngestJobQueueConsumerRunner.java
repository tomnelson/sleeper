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
package sleeper.ingest.job;

import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sleeper.configuration.jars.ObjectFactory;
import sleeper.configuration.jars.ObjectFactoryException;
import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.table.TablePropertiesProvider;
import sleeper.core.iterator.IteratorException;
import sleeper.ingest.task.IngestTaskFinishedStatus;
import sleeper.ingest.task.IngestTaskStatus;
import sleeper.ingest.task.IngestTaskStatusStore;
import sleeper.ingest.task.status.DynamoDBIngestTaskStatusStore;
import sleeper.statestore.StateStoreException;
import sleeper.statestore.StateStoreProvider;
import sleeper.utils.HadoopConfigurationProvider;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

public class IngestJobQueueConsumerRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestJobQueueConsumerRunner.class);

    private final IngestJobQueueConsumer ingestJobQueueConsumer;
    private final String taskId;
    private final IngestTaskStatusStore statusStore;
    private final Supplier<Instant> getStartTime;
    private final Supplier<Instant> getFinishTime;

    public IngestJobQueueConsumerRunner(
            IngestJobQueueConsumer queueConsumer, String taskId, IngestTaskStatusStore statusStore,
            Supplier<Instant> getStartTime, Supplier<Instant> getFinishTime) {
        this.ingestJobQueueConsumer = queueConsumer;
        this.taskId = taskId;
        this.statusStore = statusStore;
        this.getStartTime = getStartTime;
        this.getFinishTime = getFinishTime;
    }

    public IngestJobQueueConsumerRunner(
            IngestJobQueueConsumer queueConsumer, String taskId, IngestTaskStatusStore statusStore) {
        this(queueConsumer, taskId, statusStore, Instant::now, Instant::now);
    }

    public void run() throws InterruptedException, IOException, IteratorException, StateStoreException {
        Instant startTaskTime = getStartTime.get();
        IngestTaskStatus.Builder taskStatusBuilder = IngestTaskStatus.builder().taskId(taskId).startTime(startTaskTime);
        statusStore.taskStarted(taskStatusBuilder.build());
        LOGGER.info("IngestTask started at = {}", startTaskTime);

        ingestJobQueueConsumer.run();

        Instant finishTaskTime = getFinishTime.get();
        taskStatusBuilder.finishedStatus(IngestTaskFinishedStatus.builder()
                .finish(startTaskTime, finishTaskTime).build());
        statusStore.taskFinished(taskStatusBuilder.build());
        LOGGER.info("IngestTask finished at = {}", finishTaskTime);
    }

    public static void main(String[] args) throws InterruptedException, IOException, StateStoreException, IteratorException, ObjectFactoryException {
        if (1 != args.length) {
            System.err.println("Error: must have 1 argument (s3Bucket)");
            System.exit(1);
        }

        long startTime = System.currentTimeMillis();
        AmazonDynamoDB dynamoDBClient = AmazonDynamoDBClientBuilder.defaultClient();
        AmazonSQS sqsClient = AmazonSQSClientBuilder.defaultClient();
        AmazonCloudWatch cloudWatchClient = AmazonCloudWatchClientBuilder.defaultClient();
        AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();

        String s3Bucket = args[0];
        InstanceProperties instanceProperties = new InstanceProperties();
        instanceProperties.loadFromS3(s3Client, s3Bucket);

        ObjectFactory objectFactory = new ObjectFactory(instanceProperties, s3Client, "/tmp");

        String localDir = "/mnt/scratch";
        TablePropertiesProvider tablePropertiesProvider = new TablePropertiesProvider(s3Client, instanceProperties);
        StateStoreProvider stateStoreProvider = new StateStoreProvider(dynamoDBClient, instanceProperties, HadoopConfigurationProvider.getConfigurationForECS(instanceProperties));
        IngestTaskStatusStore taskStore = DynamoDBIngestTaskStatusStore.from(dynamoDBClient, instanceProperties);
        String taskId = UUID.randomUUID().toString();
        IngestJobQueueConsumer queueConsumer = new IngestJobQueueConsumer(
                objectFactory, sqsClient, cloudWatchClient, instanceProperties,
                tablePropertiesProvider, stateStoreProvider, localDir);
        IngestJobQueueConsumerRunner ingestJobQueueConsumerRunner = new IngestJobQueueConsumerRunner(
                queueConsumer, taskId, taskStore);
        ingestJobQueueConsumerRunner.run();

        s3Client.shutdown();
        LOGGER.info("Shut down s3Client");
        sqsClient.shutdown();
        LOGGER.info("Shut down sqsClient");
        dynamoDBClient.shutdown();
        LOGGER.info("Shut down dynamoDBClient");
        long finishTime = System.currentTimeMillis();
        double runTimeInSeconds = (finishTime - startTime) / 1000.0;
        LOGGER.info("IngestFromIngestJobsQueueRunner total run time = {}", runTimeInSeconds);
    }
}
