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

package sleeper.status.report.compactionjobstatus;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import sleeper.compaction.job.CompactionJob;
import sleeper.compaction.job.CompactionJobTestDataHelper;
import sleeper.compaction.job.status.CompactionJobCreatedStatus;
import sleeper.compaction.job.status.CompactionJobStartedStatus;
import sleeper.compaction.job.status.CompactionJobStatus;
import sleeper.core.partition.Partition;
import sleeper.status.report.compactionjob.CompactionJobStatusReporter;
import sleeper.status.report.compactionjob.CompactionJobStatusReporter.QueryType;
import sleeper.status.report.filestatus.FilesStatusReportTest;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

public class StatusReporterQueryUnfinishedTest {
    private CompactionJobTestDataHelper dataHelper;
    private CompactionJobStatusReporter statusReporter;

    @Before
    public void setup() {
        statusReporter = new CompactionJobStatusReporter();
        dataHelper = new CompactionJobTestDataHelper();
    }

    @Test
    public void shouldReportCompactionJobStatusWithCreatedJobs() throws Exception {
        // Given
        Partition partition = dataHelper.singlePartition();
        CompactionJob job = dataHelper.singleFileCompaction(partition);
        Instant updateTime = Instant.parse("2022-09-22T13:33:12.001Z");

        // When
        CompactionJobStatus status = CompactionJobStatus.created(job, updateTime);

        // Then
        System.out.println(statusReporter.report(Collections.singletonList(status), QueryType.UNFINISHED));
        assertThat(statusReporter.report(Collections.singletonList(status), QueryType.UNFINISHED))
                .isEqualTo(example("reports/compactionjobstatus/standard/unfinishedStandardJobCreated.txt")
                        .replace("$(jobId)", job.getId()));
    }

    @Test
    public void shouldReportSplittingCompactionJobStatusWithCreatedJobs() throws Exception {
        // Given
        CompactionJob job = dataHelper.singleFileSplittingCompaction("C", "A", "B");
        Instant updateTime = Instant.parse("2022-09-22T13:33:12.001Z");

        // When
        CompactionJobStatus status = CompactionJobStatus.created(job, updateTime);

        // Then
        System.out.println(statusReporter.report(Collections.singletonList(status), QueryType.UNFINISHED));
        assertThat(statusReporter.report(Collections.singletonList(status), QueryType.UNFINISHED))
                .isEqualTo(example("reports/compactionjobstatus/standard/unfinishedSplittingJobCreated.txt")
                        .replace("$(jobId)", job.getId()));
    }

    @Test
    public void shouldReportCompactionJobStatusWithStartedJobs() throws Exception {
        // Given
        Partition partition = dataHelper.singlePartition();
        CompactionJob job = dataHelper.singleFileCompaction(partition);
        Instant creationTime = Instant.parse("2022-09-22T13:33:12.001Z");
        Instant startedTime = Instant.parse("2022-09-22T13:34:12.001Z");
        Instant startedUpdateTime = Instant.parse("2022-09-22T13:39:12.001Z");

        // When
        CompactionJobStatus status = CompactionJobStatus.builder().jobId(job.getId())
                .createdStatus(CompactionJobCreatedStatus.from(job, creationTime))
                .startedStatus(CompactionJobStartedStatus.updateAndStartTime(startedUpdateTime, startedTime))
                .build();

        // Then
        System.out.println(statusReporter.report(Collections.singletonList(status), QueryType.UNFINISHED));
        assertThat(statusReporter.report(Collections.singletonList(status), QueryType.UNFINISHED))
                .isEqualTo(example("reports/compactionjobstatus/standard/unfinishedStandardJobStarted.txt")
                        .replace("$(jobId)", job.getId()));
    }

    @Test
    public void shouldReportSplittingCompactionJobStatusWithStartedJobs() throws Exception {
        // Given
        CompactionJob job = dataHelper.singleFileSplittingCompaction("C", "A", "B");
        Instant creationTime = Instant.parse("2022-09-22T13:33:12.001Z");
        Instant startedTime = Instant.parse("2022-09-22T13:34:12.001Z");
        Instant startedUpdateTime = Instant.parse("2022-09-22T13:39:12.001Z");

        // When
        CompactionJobStatus status = CompactionJobStatus.builder().jobId(job.getId())
                .createdStatus(CompactionJobCreatedStatus.from(job, creationTime))
                .startedStatus(CompactionJobStartedStatus.updateAndStartTime(startedUpdateTime, startedTime))
                .build();


        // Then
        System.out.println(statusReporter.report(Collections.singletonList(status), QueryType.UNFINISHED));
        assertThat(statusReporter.report(Collections.singletonList(status), QueryType.UNFINISHED))
                .isEqualTo(example("reports/compactionjobstatus/standard/unfinishedSplittingJobStarted.txt")
                        .replace("$(jobId)", job.getId()));
    }

    private static String example(String path) throws IOException {
        URL url = FilesStatusReportTest.class.getClassLoader().getResource(path);
        return IOUtils.toString(Objects.requireNonNull(url), Charset.defaultCharset());
    }
}
