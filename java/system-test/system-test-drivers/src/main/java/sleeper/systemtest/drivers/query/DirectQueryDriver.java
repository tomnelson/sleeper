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

package sleeper.systemtest.drivers.query;

import org.apache.hadoop.conf.Configuration;

import sleeper.configuration.jars.ObjectFactory;
import sleeper.configuration.properties.table.TableProperty;
import sleeper.core.iterator.CloseableIterator;
import sleeper.core.partition.PartitionTree;
import sleeper.core.record.Record;
import sleeper.core.statestore.StateStore;
import sleeper.core.statestore.StateStoreException;
import sleeper.query.QueryException;
import sleeper.query.executor.QueryExecutor;
import sleeper.query.model.Query;
import sleeper.systemtest.drivers.instance.SleeperInstanceContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterators;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class DirectQueryDriver {
    private final SleeperInstanceContext instance;

    public DirectQueryDriver(SleeperInstanceContext instance) {
        this.instance = instance;
    }

    public List<Record> getAllRecordsInTable() {
        StateStore stateStore = instance.getStateStore();
        PartitionTree tree = getPartitionTree(stateStore);
        try (CloseableIterator<Record> recordIterator =
                     executor(stateStore, tree).execute(allRecordsQuery(tree))) {
            return stream(recordIterator)
                    .collect(Collectors.toUnmodifiableList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (QueryException e) {
            throw new RuntimeException(e);
        }
    }

    private Query allRecordsQuery(PartitionTree tree) {
        return new Query.Builder(
                instance.getTableProperties().get(TableProperty.TABLE_NAME),
                UUID.randomUUID().toString(),
                List.of(tree.getRootPartition().getRegion())).build();
    }

    private PartitionTree getPartitionTree(StateStore stateStore) {
        try {
            return new PartitionTree(instance.getTableProperties().getSchema(), stateStore.getAllPartitions());
        } catch (StateStoreException e) {
            throw new RuntimeException(e);
        }
    }

    private QueryExecutor executor(StateStore stateStore, PartitionTree partitionTree) {
        try {
            QueryExecutor executor = new QueryExecutor(ObjectFactory.noUserJars(), instance.getTableProperties(),
                    stateStore, new Configuration(), Executors.newSingleThreadExecutor());
            executor.init(partitionTree.getAllPartitions(), stateStore.getPartitionToActiveFilesMap());
            return executor;
        } catch (StateStoreException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> Stream<T> stream(Iterator<T> iterator) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
    }
}
