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
package sleeper.bulkimport.starter.executor;

import com.amazonaws.services.elasticmapreduce.AmazonElasticMapReduce;
import com.amazonaws.services.elasticmapreduce.model.Application;
import com.amazonaws.services.elasticmapreduce.model.ComputeLimits;
import com.amazonaws.services.elasticmapreduce.model.ComputeLimitsUnitType;
import com.amazonaws.services.elasticmapreduce.model.Configuration;
import com.amazonaws.services.elasticmapreduce.model.HadoopJarStepConfig;
import com.amazonaws.services.elasticmapreduce.model.InstanceGroupConfig;
import com.amazonaws.services.elasticmapreduce.model.InstanceRoleType;
import com.amazonaws.services.elasticmapreduce.model.JobFlowInstancesConfig;
import com.amazonaws.services.elasticmapreduce.model.ManagedScalingPolicy;
import com.amazonaws.services.elasticmapreduce.model.MarketType;
import com.amazonaws.services.elasticmapreduce.model.RunJobFlowRequest;
import com.amazonaws.services.elasticmapreduce.model.RunJobFlowResult;
import com.amazonaws.services.elasticmapreduce.model.ScaleDownBehavior;
import com.amazonaws.services.elasticmapreduce.model.StepConfig;
import com.amazonaws.services.elasticmapreduce.model.Tag;
import com.amazonaws.services.s3.AmazonS3;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sleeper.bulkimport.job.BulkImportJob;
import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.SystemDefinedInstanceProperty;
import sleeper.configuration.properties.UserDefinedInstanceProperty;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.configuration.properties.table.TablePropertiesProvider;
import sleeper.configuration.properties.table.TableProperty;

import java.util.Map;
import java.util.stream.Collectors;

import static sleeper.configuration.properties.SystemDefinedInstanceProperty.BULK_IMPORT_BUCKET;
import static sleeper.configuration.properties.UserDefinedInstanceProperty.ID;

/**
 * An {@link Executor} which runs a bulk import job on an EMR cluster.
 */
public class EmrExecutor extends AbstractEmrExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmrExecutor.class);

    private final TablePropertiesProvider tablePropertiesProvider;
    private final AmazonElasticMapReduce emrClient;

    public EmrExecutor(AmazonElasticMapReduce emrClient,
                       InstanceProperties instancePropeties,
                       TablePropertiesProvider tablePropertiesProvider,
                       AmazonS3 amazonS3) {
        super(instancePropeties, tablePropertiesProvider, amazonS3);
        this.tablePropertiesProvider = tablePropertiesProvider;
        this.emrClient = emrClient;
    }

    @Override
    public void runJobOnPlatform(BulkImportJob bulkImportJob) {
        Map<String, String> platformSpec = bulkImportJob.getPlatformSpec();
        TableProperties tableProperties = tablePropertiesProvider.getTableProperties(bulkImportJob.getTableName());
        String bulkImportBucket = instanceProperties.get(BULK_IMPORT_BUCKET);
        String logUri = null == bulkImportBucket ? null : "s3://" + bulkImportBucket + "/logs";

        Integer maxNumberOfExecutors = Integer.max(
                Integer.parseInt(getFromPlatformSpec(TableProperty.BULK_IMPORT_EMR_INITIAL_NUMBER_OF_EXECUTORS, platformSpec, tableProperties)),
                Integer.parseInt(getFromPlatformSpec(TableProperty.BULK_IMPORT_EMR_MAX_NUMBER_OF_EXECUTORS, platformSpec, tableProperties))
        );

        String clusterName = String.join("-", "sleeper",
                instanceProperties.get(ID),
                bulkImportJob.getTableName(),
                bulkImportJob.getId());
        if (clusterName.length() > 64) {
            clusterName = clusterName.substring(0, 64);
        }

        RunJobFlowResult response = emrClient.runJobFlow(new RunJobFlowRequest()
                .withName(clusterName)
                .withInstances(createConfig(bulkImportJob, tableProperties))
                .withVisibleToAllUsers(true)
                .withSecurityConfiguration(instanceProperties.get(SystemDefinedInstanceProperty.BULK_IMPORT_EMR_SECURITY_CONF_NAME))
                .withConfigurations(new Configuration()
                        .withClassification("spark")
                        .addPropertiesEntry("maximizeResourceAllocation", "true"))
                .withManagedScalingPolicy(new ManagedScalingPolicy()
                        .withComputeLimits(new ComputeLimits()
                                .withUnitType(ComputeLimitsUnitType.Instances)
                                .withMinimumCapacityUnits(1)
                                .withMaximumCapacityUnits(maxNumberOfExecutors)
                                .withMaximumCoreCapacityUnits(3)))
                .withScaleDownBehavior(ScaleDownBehavior.TERMINATE_AT_TASK_COMPLETION)
                .withReleaseLabel(getFromPlatformSpec(TableProperty.BULK_IMPORT_EMR_RELEASE_LABEL, platformSpec, tableProperties))
                .withApplications(new Application().withName("Spark"))
                .withLogUri(logUri)
                .withServiceRole(instanceProperties.get(SystemDefinedInstanceProperty.BULK_IMPORT_EMR_CLUSTER_ROLE_NAME))
                .withJobFlowRole(instanceProperties.get(SystemDefinedInstanceProperty.BULK_IMPORT_EMR_EC2_ROLE_NAME))
                .withSteps(new StepConfig()
                        .withName("Bulk Load")
                        .withHadoopJarStep(new HadoopJarStepConfig().withJar("command-runner.jar").withArgs(constructArgs(bulkImportJob))))
                .withTags(instanceProperties.getTags().entrySet().stream()
                        .map(entry -> new Tag(entry.getKey(), entry.getValue()))
                        .collect(Collectors.toList())));

        LOGGER.info("Cluster created with ARN " + response.getClusterArn());
    }

    private JobFlowInstancesConfig createConfig(BulkImportJob bulkImportJob, TableProperties tableProperties) {
        JobFlowInstancesConfig config = new JobFlowInstancesConfig()
                .withEc2SubnetId(instanceProperties.get(UserDefinedInstanceProperty.SUBNET));

        Map<String, String> platformSpec = bulkImportJob.getPlatformSpec();
        String driverInstanceType = getFromPlatformSpec(TableProperty.BULK_IMPORT_EMR_MASTER_INSTANCE_TYPE, platformSpec, tableProperties);
        String executorInstanceType = getFromPlatformSpec(TableProperty.BULK_IMPORT_EMR_EXECUTOR_INSTANCE_TYPE, platformSpec, tableProperties);
        Integer initialNumberOfExecutors = Integer.parseInt(getFromPlatformSpec(TableProperty.BULK_IMPORT_EMR_INITIAL_NUMBER_OF_EXECUTORS, platformSpec, tableProperties));

        String marketTypeOfExecutors = getFromPlatformSpec(TableProperty.BULK_IMPORT_EMR_EXECUTOR_MARKET_TYPE, platformSpec, tableProperties);
        if (marketTypeOfExecutors == null) {
            marketTypeOfExecutors = "SPOT";
        }

        config.setInstanceGroups(Lists.newArrayList(
                new InstanceGroupConfig()
                        .withName("Executors")
                        .withInstanceType(executorInstanceType)
                        .withInstanceRole(InstanceRoleType.CORE)
                        .withInstanceCount(initialNumberOfExecutors)
                        .withMarket(MarketType.fromValue(marketTypeOfExecutors)),
                new InstanceGroupConfig()
                        .withName("Driver")
                        .withInstanceType(driverInstanceType)
                        .withInstanceRole(InstanceRoleType.MASTER)
                        .withInstanceCount(1)
        ));

        String keyName = instanceProperties.get(UserDefinedInstanceProperty.BULK_IMPORT_EC2_KEY_NAME);
        if (null != keyName) {
            config.setEc2KeyName(keyName);
        }

        return config;
    }
}
