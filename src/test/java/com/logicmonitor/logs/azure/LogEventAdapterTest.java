package com.logicmonitor.logs.azure;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.logicmonitor.logs.model.LogEntry;

public class LogEventAdapterTest {

    private LogEventAdapter adapter = new LogEventAdapter();

    @ParameterizedTest
    @CsvSource({
        "activity_storage_account.json, 2",
        "activity_webapp.json,          2",
        "resource_db_account.json,      2",
        "resource_sql.json,             2",
        "resource_vault.json,           1",
        "vm_catalina.json,              1",
        "vm_syslog.json,                1",
    })
    public void testApply(String resourceName, int expectedEntriesCount) {
        JsonObject events = TestJsonUtils.getFirstObject(resourceName);
        List<LogEntry> entries = adapter.apply(events);
        assertEquals(expectedEntriesCount, entries.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "activity_storage_account.json",
        "activity_webapp.json",
        "resource_db_account.json",
        "resource_sql.json",
        "resource_vault.json",
        "vm_catalina.json",
        "vm_syslog.json",
    })
    public void testCreateEntry(String resourceName) {
        JsonObject event = TestJsonUtils.getFirstLogEvent(resourceName);
        LogEntry entry = adapter.createEntry(event);
        assertAll(
            () -> {
                String resourceId = event.get("resourceId").getAsString();
                assertEquals(resourceId,
                        entry.getLmResourceId().get(LogEventAdapter.LM_RESOURCE_PROPERTY));
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
                String message = Optional.ofNullable(event.get("properties"))
                    .map(JsonElement::getAsJsonObject)
                    .map(properties -> properties.get("Msg"))
                    .map(JsonElement::getAsString)
                    .orElseGet(() -> TestJsonUtils.toString(event));
                assertEquals(message, entry.getMessage());
            }
        );
    }

}
