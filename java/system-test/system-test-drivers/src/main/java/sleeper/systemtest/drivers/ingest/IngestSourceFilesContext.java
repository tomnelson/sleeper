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

package sleeper.systemtest.drivers.ingest;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.hadoop.ParquetWriter;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

import sleeper.configuration.properties.table.TableProperties;
import sleeper.core.record.Record;
import sleeper.io.parquet.record.ParquetRecordWriterFactory;
import sleeper.systemtest.drivers.instance.SystemTestParameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IngestSourceFilesContext {
    private final String sourceBucketName;
    private final S3Client s3Client;

    public IngestSourceFilesContext(SystemTestParameters parameters, S3Client s3Client) {
        this.sourceBucketName = parameters.buildSourceBucketName();
        this.s3Client = s3Client;
    }

    public String getSourceBucketName() {
        return sourceBucketName;
    }

    public List<String> getIngestJobFilesInBucket(Stream<String> files) {
        return files.map(file -> sourceBucketName + "/" + file)
                .collect(Collectors.toUnmodifiableList());
    }

    public void createOrEmptySourceBucket() {
        try {
            s3Client.headBucket(builder -> builder.bucket(sourceBucketName));
            emptySourceBucket();
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(builder -> builder.bucket(sourceBucketName));
        }
    }

    public void writeFile(TableProperties tableProperties, String file, Iterator<Record> records) {
        try (ParquetWriter<Record> writer = ParquetRecordWriterFactory.createParquetRecordWriter(
                new org.apache.hadoop.fs.Path("s3a://" + sourceBucketName + "/" + file), tableProperties, new Configuration())) {
            for (Record record : (Iterable<Record>) () -> records) {
                writer.write(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void emptySourceBucket() {
        List<ObjectIdentifier> objects = s3Client.listObjectsV2Paginator(builder -> builder.bucket(sourceBucketName))
                .contents().stream()
                .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
                .collect(Collectors.toList());
        if (!objects.isEmpty()) {
            s3Client.deleteObjects(builder -> builder.bucket(sourceBucketName)
                    .delete(deleteBuilder -> deleteBuilder.objects(objects)));
        }
    }
}
