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
package sleeper.build.chunks;

import sleeper.build.github.actions.OnPushPathsDiff;
import sleeper.build.maven.InternalDependencyIndex;
import sleeper.build.maven.MavenModuleStructure;
import sleeper.build.util.ReportableException;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ProjectChunks {

    private final List<ProjectChunk> chunks;

    public ProjectChunks(List<ProjectChunk> chunks) {
        this.chunks = Collections.unmodifiableList(Objects.requireNonNull(chunks, "chunks must not be null"));
    }

    public ProjectChunk getById(String id) {
        return stream().filter(chunk -> id.equals(chunk.getId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Chunk ID not found: " + id));
    }

    public void validate(ProjectStructure project, PrintStream out) throws IOException {
        MavenModuleStructure maven = project.loadMavenStructure();
        validateAllConfigured(maven);
        validateChunkWorkflows(project, maven.internalDependencies(), out);
    }

    public void validateAllConfigured(MavenModuleStructure project) throws NotAllMavenModulesConfiguredException {
        Set<String> configuredModuleRefs = stream()
                .flatMap(chunk -> chunk.getModules().stream())
                .collect(Collectors.toSet());
        List<String> unconfiguredModuleRefs = project.allTestedModulesForProjectList()
                .filter(moduleRef -> !configuredModuleRefs.contains(moduleRef))
                .collect(Collectors.toList());
        if (!unconfiguredModuleRefs.isEmpty()) {
            throw new NotAllMavenModulesConfiguredException(unconfiguredModuleRefs);
        }
    }

    private void validateChunkWorkflows(
            ProjectStructure project, InternalDependencyIndex dependencies, PrintStream out) throws IOException {
        boolean failed = false;
        for (ProjectChunk chunk : chunks) {
            OnPushPathsDiff diff = project.loadWorkflow(chunk)
                    .getOnPushPathsDiffFromExpected(project, chunk, dependencies);
            if (!diff.getMissingEntries().isEmpty()) {
                diff.report(out, project, chunk);
                failed = true;
            }
        }
        if (failed) {
            throw new ReportableException("Failed validating chunk workflows");
        }
    }

    public Stream<ProjectChunk> stream() {
        return chunks.stream();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ProjectChunks that = (ProjectChunks) o;
        return chunks.equals(that.chunks);
    }

    @Override
    public int hashCode() {
        return chunks.hashCode();
    }

    @Override
    public String toString() {
        return chunks.toString();
    }

}
