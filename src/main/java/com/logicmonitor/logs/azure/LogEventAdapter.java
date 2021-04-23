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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.logicmonitor.logs.model.LogEntry;

/**
 * Transforms one JSON string into one or multiple log entries.<br>
 * The following formats are supported:
 * <ul>
 * <li> single log event
 * <li> {@value #AZURE_RECORDS_PROPERTY} = array of log events
 * </ul>
 */
public class LogEventAdapter implements Function<String, List<LogEntry>> {
    /**
     * Name of the JSON property containing array of log events.
     */
    public static final String AZURE_RECORDS_PROPERTY = "records";
    /**
     * Name of the LM property used to match the resources.
     */
    public static final String LM_RESOURCE_PROPERTY = "system.azure.resourceid";

    /**
     * Name of the Azure Client Id used to match the resources for activity logs.
     */
    public static final String LM_CLIENT_ID = "system.azure.clientid";
    /**
     * Used to match the category of resource for activity logs.
     */
    public static final String LM_SYSTEM_CATEGORIES = "system.cloud.category";
    /**
     * Categories of Azure activity logs generated
     */
    public static final Set AUDIT_LOG_CATEGORIES = Set.of("administrative","serviceHealth","resourcehealth","alert","autoscale","security","policy","recommendation");

    /**
     * GSON instance.
     */
    private static final Gson GSON = new GsonBuilder().create();

    private final Pattern scrubPattern;

    private final String azureClientId;

    public LogEventAdapter(String regexScrub,String ClientId) throws PatternSyntaxException {
        if (regexScrub != null) {
            scrubPattern = Pattern.compile(regexScrub);
        } else {
            scrubPattern = null;
        }
        azureClientId = ClientId;
    }

    /**
     * Gets the regex pattern used to scrub log messages.
     *
     * @return the pattern object
     */
    protected Pattern getScrubPattern() {
        return scrubPattern;
    }

    /**
     * Applies the log transformation.
     *
     * @param jsonString Azure log event as JSON string
     * @return list of log entries
     */
    @Override
    public List<LogEntry> apply(String jsonString) {
        JsonObject log = GSON.fromJson(jsonString, JsonObject.class);
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
     *
     * @param json the log object
     * @return log entry
     */
    protected LogEntry createEntry(JsonObject json) {
        LogEventMessage event = GSON.fromJson(json, LogEventMessage.class);
        LogEntry entry = new LogEntry();
        if((event.getCategory() != null && AUDIT_LOG_CATEGORIES.contains(event.getCategory().toLowerCase()))) {
            //client ID for activity logs
            entry.putLmResourceIdItem(LM_CLIENT_ID,azureClientId);
            entry.putLmResourceIdItem(LM_SYSTEM_CATEGORIES,"Azure/LMAccount");
        } else {
            // resource ID
            entry.putLmResourceIdItem(LM_RESOURCE_PROPERTY, event.getResourceId());
        }

        // timestamp as epoch
        Optional.ofNullable(event.getTime())
            .map(Instant::parse)
            .map(Instant::getEpochSecond)
            .ifPresent(entry::setTimestamp);

        // get properties from event if present
        Optional<LogEventProperties> properties = Optional.ofNullable(event.getProperties());

        // message:
        //     properties.Msg if present,
        //     else properties.Description if present,
        //     otherwise the whole JSON
        String message = properties.map(LogEventProperties::getMsg)
                .or(() -> properties.map(LogEventProperties::getDescription))
                .orElseGet(() -> GSON.toJson(json));

        if (scrubPattern != null) {
            message = scrubPattern.matcher(message).replaceAll("");
        }
        entry.setMessage(message);

        return entry;
    }

}