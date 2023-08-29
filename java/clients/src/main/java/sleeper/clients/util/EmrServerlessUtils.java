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
package sleeper.clients.util;


import software.amazon.awssdk.services.emrserverless.EmrServerlessClient;
import software.amazon.awssdk.services.emrserverless.model.ApplicationState;
import software.amazon.awssdk.services.emrserverless.model.ApplicationSummary;
import software.amazon.awssdk.services.emrserverless.model.ListApplicationsRequest;
import software.amazon.awssdk.services.emrserverless.model.ListApplicationsResponse;

import java.util.List;
import java.util.stream.Collectors;

public class EmrServerlessUtils {
    private EmrServerlessUtils() {
    }

    private static List<ApplicationState> runningStates = List.of(ApplicationState.STARTING,
        ApplicationState.STARTED, ApplicationState.CREATED, ApplicationState.CREATING);

    public static ListApplicationsResponse listActiveApplications(EmrServerlessClient emrServerlessClient) {
        ListApplicationsResponse applications =  emrServerlessClient.listApplications(ListApplicationsRequest.builder().build());
        List<ApplicationSummary> applicationsSummary = applications.applications().stream()
            .filter(application -> runningStates.contains(application.state()))
            .collect(Collectors.toList());
        return ListApplicationsResponse.builder().applications(applicationsSummary).build();
    }
}