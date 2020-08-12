package com.logicmonitor.logs.azure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.logicmonitor.logs.model.LogEntry;

/**
 * Transforms one JSON object into one or multiple log entries.<br>
 * The following formats are supported:
 * <ul>
 * <li> single log event
 * <li> {@value #AZURE_RECORDS_PROPERTY} = array of log events
 * </ul>
 */
public class LogEventAdapter implements Function<JsonObject, List<LogEntry>> {
    /**
     * Name of the JSON property containing array of log events.
     */
    public static final String AZURE_RECORDS_PROPERTY = "records";
    /**
     * Name of the LM property used to match the resources.
     */
    public static final String LM_RESOURCE_PROPERTY = "system.azure.resourceid";

    /**
     * GSON instance.
     */
    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Applies the log transformation.
     * @param log Azure log event
     * @return list of log entries
     */
    @Override
    public List<LogEntry> apply(JsonObject log) {
        // if the JSON object contains "records" array, transform its members
        return Optional.ofNullable(log.get(AZURE_RECORDS_PROPERTY))
            .filter(JsonElement::isJsonArray)
            .map(JsonElement::getAsJsonArray)
            .map(records -> StreamSupport.stream(records.spliterator(), true)
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
            )
            .orElseGet(() -> Stream.of(log))
            .map(this::createEntry)
            .collect(Collectors.toList());
    }

    /**
     * Transforms single Azure log object into log entry.
     * @param json the log object
     * @return log entry
     */
    protected LogEntry createEntry(JsonObject json) {
        LogEventMessage event = GSON.fromJson(json, LogEventMessage.class);
        LogEntry entry = new LogEntry();

        // resource ID
        entry.putLmResourceIdItem(LM_RESOURCE_PROPERTY, event.getResourceId());

        // timestamp as epoch
        Optional.of(event.getTime())
            .map(Instant::parse)
            .map(Instant::getEpochSecond)
            .ifPresent(entry::setTimestamp);

        // message: properties.Msg if present, otherwise the whole JSON
        if (event.getProperties().getMsg() != null) {
            entry.setMessage(event.getProperties().getMsg());
        } else {
            entry.setMessage(GSON.toJson(json));
        }

        return entry;
    }

}
