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

import static com.logicmonitor.logs.azure.LoggingUtils.log;
import static com.logicmonitor.logs.azure.JsonParsingUtils.parseJsonSafely;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;

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
     * Name of the Azure Client Id used to match the resources for activity logs.
     */
    public static final String LM_AZURE_ACCOUNT = "system.displayname";
    /**
     * Used to match the category of resource for activity logs.
     */
    public static final String LM_CLOUD_CATEGORY_KEY = "system.cloud.category";

    /**
     * Value for category of resource for activity logs.
     */
    public static final String LM_CLOUD_CATEGORY_VALUE = "Azure/LMAccount";
    /**
     * Categories of Azure activity logs generated
     */
    public static final Set<String> AUDIT_LOG_CATEGORIES = Set.of("administrative", "serviceHealth",
        "resourcehealth", "alert", "autoscale", "security", "policy", "recommendation");

    public static final String LM_SEVERITY = "log_level";
    public static final String LM_ACTIVITY_TYPE = "activity_type";
    public static final String LM_AZURE_RESOURCE_ID = "azure_resource_id";
    public static final String LM_CATEGORY = "category";

    public static final String LM_EVENTSOURCE = "_type";

    public static final String AZURE_SEVERITY = "level";
    public static final String AZURE_ACTIVITY_TYPE = "operationName";
    public static final String AZURE_RESOURCE_ID = "resourceId";
    public static final String AZURE_CATEGORY = "category";
    public static final String LM_TENANT_ID = "LM_TENANT_ID";

    public static final String LM_TENANT_ID_KEY = "_lm.tenantId";

    public static final Pattern RESOURCE_TYPE = Pattern.compile("/subscriptions/.*/resourceGroups/.*/providers/(?<type>[^/]*/[^/]*)/.*", Pattern.CASE_INSENSITIVE);
    /**
     * GSON instance.
     */
    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Required static metadata to be added in every LogEntry.
     */
    public static final Map<String, String> REQ_STATIC_METADATA = Map.of("_integration", "azure", "_resource.type", "Azure");

    /**
     * Required metadata key to LogEventMessage method map.
     */
    public static final Map<String, Function<LogEventMessage, String>> METADATA_KEYS_TO_GETTERS = Map
        .of(LM_SEVERITY, LogEventMessage::getLevel,
            LM_ACTIVITY_TYPE, LogEventMessage::getOperationName,
            LM_CATEGORY, LogEventMessage::getCategory,
            LM_EVENTSOURCE, LogEventAdapter::getEventSourceMetadata);


    public static final Map<String ,String> LM_METADATA_RENAME_KEYS = Map.of(
        AZURE_SEVERITY, LM_SEVERITY,
        AZURE_ACTIVITY_TYPE, LM_ACTIVITY_TYPE,
        AZURE_RESOURCE_ID, LM_AZURE_RESOURCE_ID);
    private static final Configuration JSONPATH_CONFIG = Configuration.builder().jsonProvider(new GsonJsonProvider()).build();

    private final Pattern scrubPattern;

    private final String azureClientId;

    private final String azureAccountName;

    private final Set<String> metadataDeepPath;

    public LogEventAdapter(String regexScrub, String azureClientId, String azureAccountName, String includeMetadataKeys) throws PatternSyntaxException {
        if (regexScrub != null) {
            scrubPattern = Pattern.compile(regexScrub);
        } else {
            scrubPattern = null;
        }
        this.azureClientId = azureClientId;
        this.azureAccountName = azureAccountName;
        this.metadataDeepPath = StringUtils.isNotBlank(includeMetadataKeys) ? Arrays.stream(
                StringUtils.split(includeMetadataKeys, ",")).map(StringUtils::strip)
            .collect(Collectors.toSet()) : new HashSet<>();
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
        List<LogEntry> validLogEntries = new ArrayList<>();
        try {
            JsonObject log = (JsonObject) GSON.fromJson(parseJsonSafely(jsonString), JsonObject.class);
            Optional.ofNullable(log.get(AZURE_RECORDS_PROPERTY))
                .filter(JsonElement::isJsonArray)
                .map(JsonElement::getAsJsonArray)
                .map(records -> StreamSupport.stream(records.spliterator(), true)
                    .filter(JsonElement::isJsonObject)
                    .map(JsonElement::getAsJsonObject)
                )
                .orElseGet(() -> Stream.of(log))
                .map(this::createEntry)
                .forEach(validLogEntries::add);
        } catch (JsonSyntaxException e) {
            log(Level.INFO, "Error while processing Json and applying log transformation: " + e.getMessage());
        }
        return validLogEntries;
    }

    /**
     * Transforms single Azure log object into log entry.
     *
     * @param json the log object
     * @return log entry
     */
    protected LogEntry createEntry(JsonObject json) {
        LogEventMessage event = GSON.fromJson(parseJsonSafely(json.toString()), LogEventMessage.class);
        LogEntry entry = new LogEntry();
        if ((azureAccountName != null && StringUtils.isNotBlank(azureAccountName)) && (event.getCategory() != null) && (AUDIT_LOG_CATEGORIES.contains(event.getCategory().toLowerCase()))) {
            //client ID and Azure account for activity logs
            entry.putLmResourceIdItem(LM_CLIENT_ID, azureClientId);
            entry.putLmResourceIdItem(LM_CLOUD_CATEGORY_KEY, LM_CLOUD_CATEGORY_VALUE);
            entry.putLmResourceIdItem(LM_AZURE_ACCOUNT, azureAccountName);
        } else if ((event.getCategory() != null) && (AUDIT_LOG_CATEGORIES.contains(event.getCategory().toLowerCase()))) {
            //client ID for activity logs
            entry.putLmResourceIdItem(LM_CLIENT_ID, azureClientId);
            entry.putLmResourceIdItem(LM_CLOUD_CATEGORY_KEY, LM_CLOUD_CATEGORY_VALUE);
        } else {
            // resource ID
            entry.putLmResourceIdItem(LM_RESOURCE_PROPERTY, event.getResourceId());
        }

        // timestamp as epoch
        try {
            Optional.ofNullable(event.getTime())
                .map(Instant::parse)
                .map(Instant::getEpochSecond)
                .ifPresent(entry::setTimestamp);
        } catch (Exception e) {
            entry.setTimestamp(System.currentTimeMillis());
        }

        // get properties from event if present
        Optional<LogEventProperties> properties = Optional.ofNullable(event.getProperties());

        // message:
        //     properties.Msg if present,
        //     else properties.Description if present,
        //     otherwise the whole JSON
        String message = properties.map(LogEventProperties::getMsg)
                .or(() -> properties.map(LogEventProperties::getDescription))
                .orElseGet(() -> GSON.toJson(json));

        Map<String, String> metadata = new HashMap<>();
        for (String key : METADATA_KEYS_TO_GETTERS.keySet()) {
            Function<LogEventMessage, String> getter = METADATA_KEYS_TO_GETTERS.get(key);
            String metadataVal = getter!=null ? getter.apply(event) : null;
            if (StringUtils.isNotBlank(metadataVal)) {
                metadata.put(key, metadataVal);
            }
        }
        // Add static metadata
        metadata.putAll(REQ_STATIC_METADATA);
        // Add metadata for includeMetadataKeys
        if (!metadataDeepPath.isEmpty()) {
            metadata.putAll(addMissingMetadataFromJsonEvent(json));
        }

        String tenantId = System.getenv(LM_TENANT_ID);
        if (StringUtils.isNotBlank(tenantId)) {
            metadata.put(LM_TENANT_ID_KEY, tenantId);
        }

        entry.setMetadata(metadata);
        if (scrubPattern != null) {
            message = scrubPattern.matcher(message).replaceAll("");
        }
        entry.setMessage(message);

        return entry;
    }

    private Map<String, String> addMissingMetadataFromJsonEvent(JsonObject event) {

        Map<String, String> additionalMetadata = new HashMap<>();
        ReadContext jsonPathContext = JsonPath.using(JSONPATH_CONFIG).parse(event);

        for (String metadataDeepKey : metadataDeepPath) {
            final String finalMetadataKey =
                LM_METADATA_RENAME_KEYS.getOrDefault(metadataDeepKey, metadataDeepKey);

            try {
                final String jsonPath = "$." + metadataDeepKey;
                Object val = jsonPathContext.read(jsonPath);

                //TODO we need to remove flattening when data sdk handles nested json metadata internally
                reFlat(finalMetadataKey, (JsonElement) val, additionalMetadata);

            } catch (Exception e) {
                // skip this key
            }
        }
        return additionalMetadata;
    }

    private void reFlat(String baseKey, JsonElement node, Map<String, String> flattenedMap) {
        if (node.isJsonPrimitive()) {
            // get value as string
            try {
                flattenedMap.put(baseKey, node.getAsString());
            } catch (Exception e) {
                // value could be int or double or other primitive type
                flattenedMap.put(baseKey, node.toString());
            }

        } else if (node.isJsonObject()) {

            // iterate
            JsonObject jsonObject = (JsonObject) node;
            String pathPrefix = StringUtils.isBlank(baseKey) ? "" : baseKey + ".";

            jsonObject.entrySet();
            for (Entry<String, com.google.gson.JsonElement> e : jsonObject.entrySet()) {
                reFlat(pathPrefix + e.getKey(), e.getValue(), flattenedMap);
            }
        } else if (node.isJsonArray()) {
            //iterate through array
            JsonArray jsonArray = (JsonArray) node;
            for (int i = 0; i < jsonArray.size(); i++) {
                reFlat(baseKey + "." + i, jsonArray.get(i), flattenedMap);
            }
        } else if (node.isJsonNull()) {
            // ignore
        }
    }

    public static String getEventSourceMetadata(LogEventMessage logEventMessage) {
        if (StringUtils.isNotBlank(logEventMessage.getResourceId())) {
            Matcher matcher = RESOURCE_TYPE.matcher(logEventMessage.getResourceId());
            if (matcher.find()) {
                return matcher.group("type");
            }
        }
        return StringUtils.EMPTY;
    }

}