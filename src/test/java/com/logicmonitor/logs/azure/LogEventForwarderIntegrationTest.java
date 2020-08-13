/*
 * Copyright (C) 2020 LogicMonitor, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.logicmonitor.logs.azure;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import com.google.gson.JsonArray;
import com.logicmonitor.logs.LMLogsApi;
import com.logicmonitor.logs.invoker.ServerConfiguration;
import com.logicmonitor.logs.model.LogEntry;
import com.logicmonitor.logs.model.LogResponse;
import com.microsoft.azure.functions.ExecutionContext;

public class LogEventForwarderIntegrationTest extends JerseyTest {

    protected static final String TEST_ID = "testId";
    protected static final String TEST_KEY = "testKey";
    protected static final String TEST_REQUEST_ID = "testRequestId";

    protected static ExecutionContext mockExecutionContext;

    @Path("/rest")
    public static class LogIngestResource {
        static List<LogEntry> receivedEntries;

        @Path("/log/ingest")
        @POST
        @Produces(MediaType.APPLICATION_JSON)
        @Consumes(MediaType.APPLICATION_JSON)
        public Response doPost(List<LogEntry> entries) {
            receivedEntries = entries;

            return Response
                .status(Status.ACCEPTED)
                .entity(new LogResponse().success(true))
                .header(LMLogsApi.REQUEST_ID_HEADER, TEST_REQUEST_ID)
                .build();
        }
    }

    @BeforeClass
    public static void setupMocks() {
        mockExecutionContext = mock(ExecutionContext.class);
        when(mockExecutionContext.getLogger())
            .thenReturn(mock(Logger.class));
    }

    @Override
    protected Application configure() {
        LogIngestResource.receivedEntries = null;
        forceSet(TestProperties.CONTAINER_PORT, "0");
        return new ResourceConfig(LogIngestResource.class);
    }

    @Before
    public void overrideClientBaseUrl() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_ACCESS_ID, TEST_ID)
            .and(LogEventForwarder.PARAMETER_ACCESS_KEY, TEST_KEY)
            .execute(() -> {
                LMLogsApi api = LogEventForwarder.getApi();
                URI testBaseUrl = getBaseUri().resolve(
                        URI.create(api.getApiClient().getBasePath()).getPath());
                api.getApiClient().setServers(List.of(
                        new ServerConfiguration(testBaseUrl.toString(), null, Map.of())));
            }
        );
    }

    @Test
    public void testForward() {
        JsonArray logEvents = TestJsonUtils.mergeArrays(
                "activity_storage_account.json",
                "activity_webapp.json",
                "resource_db_account.json",
                "resource_sql.json",
                "resource_vault.json",
                "vm_catalina.json",
                "vm_syslog.json");
        new LogEventForwarder().forward(logEvents, mockExecutionContext);

        assertNotNull(LogIngestResource.receivedEntries);
        assertAll(
            () -> assertEquals(14, LogIngestResource.receivedEntries.size()),
            () -> LogIngestResource.receivedEntries.forEach(entry -> assertNotNull(
                    entry.getMessage())),
            () -> LogIngestResource.receivedEntries.forEach(entry -> assertNotNull(
                    entry.getTimestamp())),
            () -> LogIngestResource.receivedEntries.forEach(entry -> assertNotNull(
                    entry.getLmResourceId().get(LogEventAdapter.LM_RESOURCE_PROPERTY)))
        );
    }

    @Test
    public void testForwardEmptyArray() {
        new LogEventForwarder().forward(new JsonArray(), mockExecutionContext);

        assertNull(LogIngestResource.receivedEntries);
    }

}
