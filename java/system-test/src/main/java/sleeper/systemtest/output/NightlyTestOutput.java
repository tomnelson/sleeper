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
package sleeper.systemtest.output;

import com.amazonaws.services.s3.AmazonS3;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class NightlyTestOutput {

    private static final PathMatcher LOG_FILE_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**.log");
    private static final PathMatcher STATUS_FILE_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**.status");

    @NotNull
    private final List<Path> logFiles;
    @NotNull
    private final Map<String, Integer> statusCodeByTest;

    private NightlyTestOutput(Builder builder) {
        logFiles = Objects.requireNonNull(builder.logFiles, "logFiles must not be null");
        statusCodeByTest = Objects.requireNonNull(builder.statusCodeByTest, "statusCodeByTest must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static NightlyTestOutput from(Path directory) throws IOException {
        List<Path> logFiles = new ArrayList<>();
        List<Path> statusFiles = new ArrayList<>();
        forEachFileIn(directory, file -> {
            if (LOG_FILE_MATCHER.matches(file)) {
                logFiles.add(file);
            } else if (STATUS_FILE_MATCHER.matches(file)) {
                statusFiles.add(file);
            }
        });
        return builder()
                .logFiles(logFiles)
                .statusCodeByTest(readStatusFiles(statusFiles))
                .build();
    }

    private static void forEachFileIn(Path directory, Consumer<Path> action) throws IOException {
        try (Stream<Path> entriesInDirectory = Files.list(directory)) {
            entriesInDirectory.filter(Files::isRegularFile).forEach(action);
        }
    }

    private static Map<String, Integer> readStatusFiles(List<Path> statusFiles) throws IOException {
        Map<String, Integer> statusCodeByTest = new HashMap<>();
        for (Path statusFile : statusFiles) {
            statusCodeByTest.put(readTestName(statusFile), Integer.parseInt(Files.readString(statusFile)));
        }
        return statusCodeByTest;
    }

    private static String readTestName(Path statusFile) {
        String fullFilename = statusFile.getFileName().toString();
        return fullFilename.substring(0, fullFilename.lastIndexOf('.'));
    }

    public void uploadToS3(AmazonS3 s3Client, String bucketName, NightlyTestTimestamp timestamp) {
        logFiles.forEach(path -> s3Client.putObject(bucketName,
                getPathInS3(timestamp, path),
                path.toFile()));
    }

    private static String getPathInS3(NightlyTestTimestamp timestamp, Path filePath) {
        return timestamp.getS3FolderName() + "/" + filePath.getFileName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        NightlyTestOutput that = (NightlyTestOutput) o;
        return logFiles.equals(that.logFiles) && statusCodeByTest.equals(that.statusCodeByTest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(logFiles, statusCodeByTest);
    }

    @Override
    public String toString() {
        return "NightlyTestOutput{" +
                "logFiles=" + logFiles +
                ", statusCodeByTest=" + statusCodeByTest +
                '}';
    }

    public static final class Builder {
        private List<Path> logFiles = Collections.emptyList();
        private Map<String, Integer> statusCodeByTest = Collections.emptyMap();

        private Builder() {
        }

        public Builder logFiles(List<Path> logFiles) {
            this.logFiles = logFiles;
            return this;
        }

        public Builder statusCodeByTest(Map<String, Integer> statusCodeByTest) {
            this.statusCodeByTest = statusCodeByTest;
            return this;
        }

        public NightlyTestOutput build() {
            return new NightlyTestOutput(this);
        }
    }
}
