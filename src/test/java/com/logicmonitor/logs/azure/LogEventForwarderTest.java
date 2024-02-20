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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.logicmonitor.sdk.data.Configuration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class LogEventForwarderTest {

    protected static final String TEST_AZURE_CLIENT_ID = "testClientId";

    @ParameterizedTest
    @CsvSource({
        "company,    ,       0,         false,    \\d                       ",
        "company,    0,      ,          true,     [\\w-.#]+@[\\w-.]+        ",
        "company,    333,    4444,      false,    \\d+\\.\\d+\\.\\d+\\.\\d+ ",
        "company,    55555,  666666,    false,                             ",
    })
    public void testConfigurationParameters(String companyName, Integer connectTimeout,
            Integer readTimeout, Boolean debugging, String regexScrub) throws Exception {

        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, companyName)
                .and(LogEventForwarder.PARAMETER_LM_AUTH,"{\"LM_ACCESS_ID\": \"id\", \"LM_ACCESS_KEY\" : \"key\", \"LM_BEARER_TOKEN\" : \"\"}")
            .and(LogEventForwarder.PARAMETER_AZURE_CLIENT_ID, "azureClientId")
            .and(LogEventForwarder.PARAMETER_AZURE_ACCOUNT_NAME, "azureAccountName")
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
                    () -> assertEquals(regexScrub,
                            regexScrub != null ? adapter.getScrubPattern().pattern() : adapter.getScrubPattern())
                );
            }
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
        "windows_vm_log.json,           1",
    })
    public void testProcessEvents(String resourceName, int expectedEntriesCount) throws Exception{
        withEnvironmentVariable(LogEventForwarder.PARAMETER_AZURE_CLIENT_ID, TEST_AZURE_CLIENT_ID)
                .execute(() -> {
        List<String> events = TestJsonUtils.getJsonStringList(resourceName);
        List<LogEntry> entries = LogEventForwarder.processEvents(events);
        List<String> invalidJsonEvents = List.of("invalidJson");
        List<LogEntry> entriesTestWithInvalidJson = LogEventForwarder.processEvents(invalidJsonEvents);
        assertNotNull(entries);
        assertAll(
            () -> assertTrue(entriesTestWithInvalidJson.isEmpty()),
            () -> assertEquals(expectedEntriesCount, entries.size()),
            () -> entries.forEach(entry -> assertNotNull(entry.getMessage())),
            () -> entries.forEach(entry -> assertNotNull(entry.getTimestamp())),
            () -> entries.forEach((entry) -> {
                if (entry.getLmResourceId().containsKey(LogEventAdapter.LM_CLIENT_ID)) {
                    assertNotNull(entry.getLmResourceId().get(LogEventAdapter.LM_CLIENT_ID));
                } else {
                    assertNotNull(entry.getLmResourceId().get(LogEventAdapter.LM_RESOURCE_PROPERTY));
                }
            })
        );
    });
    }

    @ParameterizedTest
    @CsvSource({
        "activity_storage_account.json, 'testClientId'",
        "activity_webapp.json,          'testClientId'",
        "resource_db_account.json,      '/SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.DOCUMENTDB/DATABASEACCOUNTS/ACCOUNT-2 /SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.DOCUMENTDB/DATABASEACCOUNTS/ACCOUNT-1'",
        "resource_sql.json,             '/SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.SQL/SERVERS/DBSERVER-1/DATABASES/DB-2 /SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.SQL/SERVERS/SERVER-1/DATABASES/DB-1'",
        "resource_vault.json,           '/SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.KEYVAULT/VAULTS/VAULT-1'",
        "vm_catalina.json,              '/subscriptions/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/resourceGroups/resource-group-1/providers/Microsoft.Compute/virtualMachines/vm-1'",
        "vm_syslog.json,                '/subscriptions/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/resourceGroups/resource-group-1/providers/Microsoft.Compute/virtualMachines/vm-1'",
        "windows_vm_log.json,           '/subscriptions/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/resourceGroups/resource-group-1/providers/Microsoft.Compute/virtualMachines/vm-win'",
    })
    public void testgetResourceIds(String resourceName, String expectedIds) throws Exception{
        withEnvironmentVariable(LogEventForwarder.PARAMETER_AZURE_CLIENT_ID, TEST_AZURE_CLIENT_ID)
                .execute(() -> {
                    List<String> events = TestJsonUtils.getJsonStringList(resourceName);
                    List<LogEntry> entries = LogEventForwarder.processEvents(events);
                    Set<String> ids = LogEventForwarder.getResourceIds(entries);
                    assertNotNull(ids);
                    assertEquals(Arrays.stream(expectedIds.split(" ")).collect(Collectors.toSet()), ids);
                });
    }

    @ParameterizedTest
    @CsvSource({
        "activity_storage_account.json, '/SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.STORAGE/STORAGEACCOUNTS/ACCOUNT-1 /SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.STORAGE/STORAGEACCOUNTS/account-1',      'MICROSOFT.STORAGE/STORAGEACCOUNTS'",
        "activity_webapp.json,          '/SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.WEB/SERVERFARMS/ASP-1 /SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.WEB/SERVERFARMS/ASP-1',      'MICROSOFT.WEB/SERVERFARMS'",
        "resource_db_account.json,      '/SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.DOCUMENTDB/DATABASEACCOUNTS/ACCOUNT-2 /SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.DOCUMENTDB/DATABASEACCOUNTS/ACCOUNT-1',      'MICROSOFT.DOCUMENTDB/DATABASEACCOUNTS'",
        "resource_sql.json,             '/SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.SQL/SERVERS/DBSERVER-1/DATABASES/DB-2 /SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.SQL/SERVERS/SERVER-1/DATABASES/DB-1',        'MICROSOFT.SQL/SERVERS MICROSOFT.SQL/SERVERS'",
        "resource_vault.json,           '/SUBSCRIPTIONS/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/RESOURCEGROUPS/RESOURCE-GROUP-1/PROVIDERS/MICROSOFT.KEYVAULT/VAULTS/VAULT-1',      'MICROSOFT.KEYVAULT/VAULTS'",
        "vm_catalina.json,              '/subscriptions/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/resourceGroups/resource-group-1/providers/Microsoft.Compute/virtualMachines/vm-1',     'Microsoft.Compute/virtualMachines'",
        "vm_syslog.json,                '/subscriptions/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/resourceGroups/resource-group-1/providers/Microsoft.Compute/virtualMachines/vm-1',     'Microsoft.Compute/virtualMachines'",
        "windows_vm_log.json,           '/subscriptions/a0b1c2d3-e4f5-g6h7-i8j9-k0l1m2n3o4p5/resourceGroups/resource-group-1/providers/Microsoft.Compute/virtualMachines/vm-win',       'Microsoft.Compute/virtualMachines'",
    })
    public void testMetadata(String resourceName, String expectedIds, String expectedType)
        throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_AZURE_CLIENT_ID, TEST_AZURE_CLIENT_ID)
            .and(LogEventForwarder.PARAMETER_INCLUDE_METADATA_KEYS, "resourceId,identity")
            .execute(() -> {
                LogEventAdapter adapter = LogEventForwarder.configureAdapter();
                List<String> events = TestJsonUtils.getJsonStringList(resourceName);
                List<LogEntry> entries = events.stream()
                    .map(adapter)
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
                Set<String> metadataIds = entries.stream()
                    .map(e -> e.getMetadata().get(LogEventAdapter.LM_AZURE_RESOURCE_ID)).collect(
                        Collectors.toSet());
                Set<String> metadataType = entries.stream()
                    .map(e -> e.getMetadata().get(LogEventAdapter.LM_EVENTSOURCE)).collect(
                        Collectors.toSet());
                assertNotNull(metadataIds);
                assertNotNull(metadataType);
                assertEquals(Arrays.stream(expectedIds.split(" ")).collect(Collectors.toSet()),
                    metadataIds);
                assertEquals(Arrays.stream(expectedType.split(" ")).collect(Collectors.toSet()),
                    metadataType);

            });
    }

}
