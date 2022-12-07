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
package sleeper.ingest.task;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sleeper.ingest.task.TestIngestTaskStatus.finishedNoJobsDefault;
import static sleeper.ingest.task.TestIngestTaskStatus.startedBuilderWithDefaults;

public class WriteToMemoryIngestTaskStatusStoreTest {

    private final IngestTaskStatusStore store = new WriteToMemoryIngestTaskStatusStore();

    @Test
    public void shouldListOneStartedTaskWhenStored() {
        // Given
        IngestTaskStatus started = startedBuilderWithDefaults().build();

        // When
        store.taskStarted(started);

        // Then
        assertThat(store.getAllTasks()).containsExactly(started);
    }

    @Test
    public void shouldListOneFinishedTaskWhenStored() {
        // Given
        IngestTaskStatus.Builder builder = startedBuilderWithDefaults();
        IngestTaskStatus started = builder.build();
        IngestTaskStatus finished = finishedNoJobsDefault(builder);

        // When
        store.taskStarted(started);
        store.taskFinished(finished);

        // Then
        assertThat(store.getAllTasks()).containsExactly(finished);
    }

    @Test
    public void shouldRefuseSameTaskStartedMultipleTimes() {
        // Given
        IngestTaskStatus started = startedBuilderWithDefaults().build();
        store.taskStarted(started);

        // When / Then
        assertThatThrownBy(() -> store.taskStarted(started))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void shouldRefuseTaskFinishedButNotStarted() {
        // Given
        IngestTaskStatus finished = finishedNoJobsDefault();

        // When / Then
        assertThatThrownBy(() -> store.taskFinished(finished))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void shouldRefuseFinishedTaskReportedAsStarted() {
        // Given
        IngestTaskStatus finished = finishedNoJobsDefault();

        // When / Then
        assertThatThrownBy(() -> store.taskStarted(finished))
                .isInstanceOf(IllegalStateException.class);
    }
}
