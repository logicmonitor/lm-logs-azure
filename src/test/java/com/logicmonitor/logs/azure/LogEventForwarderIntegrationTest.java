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

import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import com.logicmonitor.sdk.data.Configuration;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.microsoft.azure.functions.ExecutionContext;

public class LogEventForwarderIntegrationTest extends JerseyTest {

    protected static final String TEST_ID = "testId";
    protected static final String TEST_KEY = "testKey";
    protected static final String TEST_REQUEST_ID = "testRequestId";
    protected static final Pattern TEST_SCRUB_PATTERN = Pattern.compile("\\d");
    protected static final String TEST_AZURE_CLIENT_ID = "testClientId";
    protected static final String TEST_AZURE_ACCOUNT_NAME = "testAccountName";
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
                .header("X-Request-ID", TEST_REQUEST_ID)
                .build();
        }
    }

    @BeforeClass
    public static void setupMocks() {
        mockExecutionContext = mock(ExecutionContext.class);
        when(mockExecutionContext.getLogger())
            .thenReturn(mock(Logger.class));
    }

    @Before
    public void setupLogEventForwarder() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_LM_AUTH,"{\"LM_ACCESS_ID\": \"id\", \"LM_ACCESS_KEY\" : \"key\", \"LM_BEARER_TOKEN\" : \"\"}")
            .and(LogEventForwarder.PARAMETER_REGEX_SCRUB, TEST_SCRUB_PATTERN.pattern())
            .and(LogEventForwarder.PARAMETER_AZURE_CLIENT_ID, TEST_AZURE_CLIENT_ID)
            .and(LogEventForwarder.PARAMETER_AZURE_ACCOUNT_NAME, TEST_AZURE_ACCOUNT_NAME)
            .and(LogEventForwarder.PARAMETER_COMPANY_NAME, "localhost")
                .execute(() -> {
                LogEventForwarder.getAdapter();
            });
    }

    @Override
    protected Application configure() {
        LogIngestResource.receivedEntries = null;
        forceSet(TestProperties.CONTAINER_PORT, "0");
        return new ResourceConfig(LogIngestResource.class);
    }

    @ParameterizedTest
    @CsvSource({
            "company1,    ,       0,         false,    \\d                       ",
            "company2,    0,      ,          true,     [\\w-.#]+@[\\w-.]+        ",
            "company3,    3334,    44445,      false,    \\d+\\.\\d+\\.\\d+\\.\\d+ ",
            "company4,    555555,  6666666,    false,                             ",
    })
    public void testConfigurationParameters(String companyName, Integer connectTimeout,
                                            Integer readTimeout, Boolean debugging, String regexScrub) throws Exception {

        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, companyName)
                .and(LogEventForwarder.PARAMETER_LM_AUTH,"{\"LM_ACCESS_ID\": \"id\", \"LM_ACCESS_KEY\" : \"key\", \"LM_BEARER_TOKEN\" : \"\"}")
                .and(LogEventForwarder.PARAMETER_AZURE_CLIENT_ID, "azureClientId")
                .and(LogEventForwarder.PARAMETER_CONNECT_TIMEOUT,
                        connectTimeout != null ? connectTimeout.toString() : null)
                .and(LogEventForwarder.PARAMETER_READ_TIMEOUT,
                        readTimeout != null ? readTimeout.toString() : null)
                .and(LogEventForwarder.PARAMETER_DEBUGGING,
                        debugging != null ? debugging.toString() : null)
                .and(LogEventForwarder.PARAMETER_REGEX_SCRUB, regexScrub)
                .execute(() -> {
                            LogEventAdapter adapter = LogEventForwarder.configureAdapter();
                            Configuration conf = LogEventForwarder.createDataSdkConfig();
                            assertAll(
                                    () -> assertEquals(companyName,
                                            conf.getCompany()),
                                    () -> assertTrue(conf.checkAuthentication()),
                                    () -> assertEquals(regexScrub,
                                            regexScrub != null ? adapter.getScrubPattern().pattern() : adapter.getScrubPattern())
                            );
                        }
                );
    }

    @Test
    public void testForwardEmptyList() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_LM_AUTH,"{\"LM_ACCESS_ID\": \"id\", \"LM_ACCESS_KEY\" : \"key\", \"LM_BEARER_TOKEN\" : \"\"}")
                .and(LogEventForwarder.PARAMETER_REGEX_SCRUB, TEST_SCRUB_PATTERN.pattern())
                .and(LogEventForwarder.PARAMETER_AZURE_CLIENT_ID, TEST_AZURE_CLIENT_ID)
                .and(LogEventForwarder.PARAMETER_AZURE_ACCOUNT_NAME, TEST_AZURE_ACCOUNT_NAME)
                .and(LogEventForwarder.PARAMETER_COMPANY_NAME, "localhost")
                .execute(() -> {
        new LogEventForwarder().forward(List.of(), mockExecutionContext);
        assertNull(LogIngestResource.receivedEntries);
    });
    }

}
