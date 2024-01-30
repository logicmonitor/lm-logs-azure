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
import static org.mockito.Mockito.when;

import com.google.gson.*;

import java.time.Instant;
import java.util.*;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import org.junit.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        LogEventAdapter adapter = new LogEventAdapter(null, "azure_client_id", null);
        List<LogEntry> entries = adapter.apply(events);
        assertEquals(expectedEntriesCount, entries.size());
    }

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
    public void testApplyWithErrorHandling(String resourceName, int expectedEntriesCount) {
        String events = TestJsonUtils.getFirstJsonString(resourceName);
        LogEventAdapter adapter = new LogEventAdapter(null, "azure_client_id", "resourceId");
        assertAll(
            () -> {
                List<LogEntry> result = adapter.apply(events);
                assertEquals(expectedEntriesCount, result.size());
            },
            () -> {
                String invalidJsonString = "InvalidJson";
                List<LogEntry> result = adapter.apply(invalidJsonString);
                assertEquals(Collections.emptyList(), result);
            }
        );
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
    public void testCreateEntry(String resourceName, String propertyName, String regexScrub,
        String azureClientId) {
        JsonObject event = TestJsonUtils.getFirstLogEvent(resourceName);
        LogEventAdapter adapter = new LogEventAdapter(regexScrub, azureClientId, null);
        LogEntry entry = adapter.createEntry(event);
        assertAll(
            () -> {
                if (azureClientId != null) {
                    assertEquals(azureClientId,
                        entry.getLmResourceId().get(LogEventAdapter.LM_CLIENT_ID));
                } else {
                    String resourceId = event.get("resourceId").getAsString();
                    assertEquals(resourceId,
                        entry.getLmResourceId().get(LogEventAdapter.LM_RESOURCE_PROPERTY));
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
                    message = event.get("properties").getAsJsonObject().get(propertyName)
                        .getAsString();
                } else {
                    message = TestJsonUtils.toString(event);
                }
                if (regexScrub != null) {
                    message = message.replaceAll(regexScrub, "");
                }
                assertEquals(message, entry.getMessage());
            },
            () -> {
                Map<String, String> metadata = entry.getMetadata();
                LogEventMessage event_msg = new GsonBuilder().create()
                    .fromJson(event, LogEventMessage.class);
                for (String key : metadata.keySet()) {
                    if (LogEventAdapter.REQ_STATIC_METADATA.containsKey(key)) {
                        assertEquals(metadata.get(key),
                            LogEventAdapter.REQ_STATIC_METADATA.get(key));

                    } else {
                        assertEquals(metadata.get(key),
                            LogEventAdapter.METADATA_KEYS_TO_GETTERS.get(key).apply(event_msg));
                    }
                }

            }
        );
    }


    @ParameterizedTest
    @CsvSource({
        "activity_storage_account.json, ,                                              , xyz",
        "activity_webapp1.json,          ,            [\\w-.#]+@[\\w-.]+                , abc",
        "resource_db_account.json,      ,            \\d+\\.\\d+\\.\\d+\\.\\d+         ,    ",
        "resource_sql.json,             ,            '\"SubscriptionId\":\"[^\"]+\",'  ,    ",
        "resource_vault.json,           ,            ''|\"                             ,    ",
        "vm_catalina.json,              Msg,         .                                 ,    ",
        "vm_syslog.json,                Msg,         \\d                               ,    ",
        "windows_vm_log.json,           Description,                                   ,    ",
        "resource_metrics.json,         ,                                              ,    "
    })
    public void testCreateEntryForGSON(String resourceName, String propertyName, String regexScrub,
        String azureClientId) {
        JsonObject event = TestJsonUtils.getFirstLogEvent(resourceName);
        LogEventAdapter adapter = new LogEventAdapter(regexScrub, azureClientId, null);
        LogEntry entry = adapter.createEntry(event);
        assertAll(
            () -> {
                if (azureClientId != null) {
                    assertEquals(azureClientId,
                        entry.getLmResourceId().get(LogEventAdapter.LM_CLIENT_ID));
                } else {
                    String resourceId = event.get("resourceId").getAsString();
                    assertEquals(resourceId,
                        entry.getLmResourceId().get(LogEventAdapter.LM_RESOURCE_PROPERTY));
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
                    message = event.get("properties").getAsJsonObject().get(propertyName)
                        .getAsString();
                } else {
                    message = TestJsonUtils.toString(event);
                }
                if (regexScrub != null) {
                    message = message.replaceAll(regexScrub, "");
                }
                assertEquals(message, entry.getMessage());
            },
            () -> {
                Map<String, String> metadata = entry.getMetadata();
                LogEventMessage event_msg = new GsonBuilder().create()
                    .fromJson(event, LogEventMessage.class);
                for (String key : metadata.keySet()) {
                    if (LogEventAdapter.REQ_STATIC_METADATA.containsKey(key)) {
                        assertEquals(metadata.get(key),
                            LogEventAdapter.REQ_STATIC_METADATA.get(key));

                    } else {
                        assertEquals(metadata.get(key),
                            LogEventAdapter.METADATA_KEYS_TO_GETTERS.get(key).apply(event_msg));
                    }
                }

            }
        );
    }


    @Test
    public void jsonMetadataExtractionTest() {
        JsonObject event = TestJsonUtils.getFirstLogEvent("activity_webapp.json");
        LogEventAdapter adapter = new LogEventAdapter("testRegexScrub", "testAzureClientId",
            " resultType, callerIpAddress  , identity.authorization , non_existing_key, properties");
        LogEntry entry = adapter.createEntry(event);
        assertEquals(entry.getMetadata().get("resultType"), "Start");
        assertEquals(entry.getMetadata().get("callerIpAddress"), "10.10.10.10");
        assertEquals(entry.getMetadata().get("identity.authorization.scope"),
            "/subscriptions/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/resourcegroups/resource-group-1/providers/Microsoft.Web/serverfarms/ASP-1");
        assertEquals(entry.getMetadata().get("identity.authorization.action"),
            "Microsoft.Web/serverfarms/write");
        assertEquals(entry.getMetadata().get("identity.authorization.evidence.role"),
            "Subscription Admin");

        assertEquals(entry.getMetadata().get("non_existing_key"), null);
    }


}
