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

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.logicmonitor.logs.model.LogEntry;

public class LogEventAdapterTest {

    @ParameterizedTest
    @CsvSource({
        "activity_storage_account.json, 2",
        "activity_webapp.json,          2",
        "resource_db_account.json,      2",
        "resource_sql.json,             2",
        "resource_vault.json,           1",
        "vm_catalina.json,              1",
        "vm_syslog.json,                1",
        "windows_vm_log.json,           1",
        "resource_metrics.json,         1"
    })
    public void testApply(String resourceName, int expectedEntriesCount) {
        String events = TestJsonUtils.getFirstJsonString(resourceName);
        LogEventAdapter adapter = new LogEventAdapter(null, "azure_client_id");
        List<LogEntry> entries = adapter.apply(events);
        assertEquals(expectedEntriesCount, entries.size());
    }

    @ParameterizedTest
    @CsvSource({
        "activity_storage_account.json, ,                                              , xyz",
        "activity_webapp.json,          ,            [\\w-.#]+@[\\w-.]+                , abc",
        "resource_db_account.json,      ,            \\d+\\.\\d+\\.\\d+\\.\\d+         ,    ",
        "resource_sql.json,             ,            '\"SubscriptionId\":\"[^\"]+\",'  ,    ",
        "resource_vault.json,           ,            ''|\"                             ,    ",
        "vm_catalina.json,              Msg,         .                                 ,    ",
        "vm_syslog.json,                Msg,         \\d                               ,    ",
        "windows_vm_log.json,           Description,                                   ,    ",
        "resource_metrics.json,         ,                                              ,    "
    })
    public void testCreateEntry(String resourceName, String propertyName, String regexScrub,String azureClientId) {
        JsonObject event = TestJsonUtils.getFirstLogEvent(resourceName);
        LogEventAdapter adapter = new LogEventAdapter(regexScrub, azureClientId);
        LogEntry entry = adapter.createEntry(event);
        assertAll(
            () -> {
                if (azureClientId != null) {
                    assertEquals(azureClientId, entry.getLmResourceId().get(LogEventAdapter.LM_CLIENT_ID));
                } else {
                    String resourceId = event.get("resourceId").getAsString();
                    assertEquals(resourceId, entry.getLmResourceId().get(LogEventAdapter.LM_RESOURCE_PROPERTY));
                }
            },
            () -> {
                Long timestamp = Optional.ofNullable(event.get("time"))
                    .map(JsonElement::getAsString)
                    .map(Instant::parse)
                    .map(Instant::getEpochSecond)
                    .orElse(null);
                assertEquals(timestamp, entry.getTimestamp());
            },
            () -> {
                String message;
                if (propertyName != null) {
                    message = event.get("properties").getAsJsonObject().get(propertyName).getAsString();
                } else {
                    message = TestJsonUtils.toString(event);
                }
                if (regexScrub != null) {
                    message = message.replaceAll(regexScrub, "");
                }
                assertEquals(message, entry.getMessage());
            }
        );
    }

}
