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

package sleeper.status.report.ingest.task;


import sleeper.core.record.process.RecordsProcessed;
import sleeper.core.record.process.RecordsProcessedSummary;
import sleeper.ingest.task.IngestTaskFinishedStatus;
import sleeper.ingest.task.IngestTaskStatus;

import java.time.Instant;
import java.util.stream.Stream;

public class IngestTaskStatusReportTestHelper {
    private IngestTaskStatusReportTestHelper() {
    }

    public static IngestTaskStatus startedTask(String taskId, String startTime) {
        return startedTaskBuilder(taskId, startTime).build();
    }


    private static IngestTaskStatus.Builder startedTaskBuilder(String taskId, String startTime) {
        return IngestTaskStatus.builder()
                .startTime(Instant.parse(startTime))
                .taskId(taskId);
    }

    public static IngestTaskStatus finishedTask(String taskId, String startTime,
                                                String finishTime, long linesRead, long linesWritten) {
        return finishedTaskBuilder(taskId, startTime, finishTime, linesRead, linesWritten).build();
    }

    public static IngestTaskStatus finishedTask(String taskId, String startTime, String finishTime,
                                                RecordsProcessedSummary... summaries) {
        return startedTaskBuilder(taskId, startTime)
                .finished(Instant.parse(finishTime),
                        IngestTaskFinishedStatus.builder().jobSummaries(Stream.of(summaries)))
                .build();
    }

    private static IngestTaskStatus.Builder finishedTaskBuilder(String taskId, String startTime,
                                                                String finishTime, long linesRead, long linesWritten) {
        return startedTaskBuilder(taskId, startTime)
                .finished(Instant.parse(finishTime),
                        taskFinishedStatus(startTime, finishTime, linesRead, linesWritten));
    }

    private static IngestTaskFinishedStatus.Builder taskFinishedStatus(
            String startTime, String finishTime, long linesRead, long linesWritten) {
        return IngestTaskFinishedStatus.builder()
                .addJobSummary(createSummary(startTime, finishTime, linesRead, linesWritten));
    }

    private static RecordsProcessedSummary createSummary(
            String startTime, String finishTime, long linesRead, long linesWritten) {
        return new RecordsProcessedSummary(
                new RecordsProcessed(linesRead, linesWritten),
                Instant.parse(startTime), Instant.parse(finishTime));
    }
}