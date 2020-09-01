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
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.google.gson.JsonArray;
import com.logicmonitor.logs.LMLogsApi;
import com.logicmonitor.logs.LMLogsClient;
import com.logicmonitor.logs.model.LogEntry;

public class LogEventForwarderTest {

    @ParameterizedTest
    @CsvSource({
        ",           1,      2,       true",
        "company,    ,       0,       false",
        "company,    0,      ,        true",
        "company,    333,    4444,    false",
    })
    public void testConfigurationParameters(String companyName, Integer connectTimeout,
            Integer readTimeout, Boolean debugging) throws Exception {

        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, companyName)
            .and(LogEventForwarder.PARAMETER_ACCESS_ID, "id")
            .and(LogEventForwarder.PARAMETER_ACCESS_KEY, "key")
            .and(LogEventForwarder.PARAMETER_CONNECT_TIMEOUT,
                    connectTimeout != null ? connectTimeout.toString() : null)
            .and(LogEventForwarder.PARAMETER_READ_TIMEOUT,
                    readTimeout != null ? readTimeout.toString() : null)
            .and(LogEventForwarder.PARAMETER_DEBUGGING,
                    debugging != null ? debugging.toString() : null)
            .execute(() -> {
                LMLogsApi api = LogEventForwarder.configureApi();
                assertAll(
                    () -> assertEquals(companyName,
                            api.getApiClient().getCompany()),
                    () -> assertEquals(connectTimeout != null ? connectTimeout : LMLogsClient.DEFAULT_TIMEOUT,
                            api.getApiClient().getConnectTimeout()),
                    () -> assertEquals(readTimeout != null ? readTimeout : LMLogsClient.DEFAULT_TIMEOUT,
                            api.getApiClient().getReadTimeout()),
                    () -> assertEquals(debugging != null ? debugging : false,
                            api.getApiClient().isDebugging())
                );
            }
        );
    }

    @ParameterizedTest
    @CsvSource({
        ",      key",
        "id,       ",
    })
    public void testInvalidConfigurationParameters(String accessId, String accessKey) throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_ACCESS_ID, accessId)
            .and(LogEventForwarder.PARAMETER_ACCESS_KEY, accessKey)
            .execute(() ->
                assertThrows(NullPointerException.class, () -> LogEventForwarder.configureApi())
        );
    }

    @ParameterizedTest
    @CsvSource({
        "activity_storage_account.json, 2",
        "activity_webapp.json,          2",
        "resource_db_account.json,      3",
        "resource_sql.json,             2",
        "resource_vault.json,           2",
        "vm_catalina.json,              1",
        "vm_syslog.json,                2",
    })
    public void testProcessEvents(String resourceName, int expectedEntriesCount) {
        JsonArray events = TestJsonUtils.getArray(resourceName);
        List<LogEntry> entries = LogEventForwarder.processEvents(events);
        assertNotNull(entries);
        assertAll(
            () -> assertEquals(expectedEntriesCount, entries.size()),
            () -> entries.forEach(entry -> assertNotNull(entry.getMessage())),
            () -> entries.forEach(entry -> assertNotNull(entry.getTimestamp())),
            () -> entries.forEach(entry -> assertNotNull(
                    entry.getLmResourceId().get(LogEventAdapter.LM_RESOURCE_PROPERTY)))
        );
    }

}
