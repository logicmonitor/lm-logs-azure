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

import static com.logicmonitor.logs.azure.JsonParsingUtils.removeQuotesAndUnescape;
import static com.logicmonitor.logs.azure.LoggingUtils.log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.logicmonitor.sdk.data.Configuration;
import com.logicmonitor.sdk.data.api.Logs;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.Cardinality;
import com.microsoft.azure.functions.annotation.EventHubTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.openapitools.client.ApiCallback;
import org.openapitools.client.ApiResponse;

/**
 * Azure Function forwarding Azure logs to LogicMonitor endpoint.<br> It is parametrized using the
 * following environment variables:
 * <ul>
 * <li>{@value #PARAMETER_COMPANY_NAME} company in the target URL '{company}.logicmonitor.com'
 * <li>{@value #PARAMETER_ACCESS_ID} LogicMonitor access ID
 * <li>{@value #PARAMETER_ACCESS_KEY} LogicMonitor access key
 * <li>{@value #PARAMETER_CONNECT_TIMEOUT} Connection timeout in milliseconds (default 10000)
 * <li>{@value #PARAMETER_READ_TIMEOUT} Read timeout in milliseconds (default 10000)
 * <li>{@value #PARAMETER_DEBUGGING} HTTP client debugging
 * <li>{@value #PARAMETER_REGEX_SCRUB} Regex to scrub text from logs
 * <li>{@value #PARAMETER_AZURE_CLIENT_ID} Azure Application Client ID
 * </ul>
 */
public class LogEventForwarder {

    /**
     * Parameter: company in the target URL '{company}.logicmonitor.com'.
     */
    public static final String PARAMETER_COMPANY_NAME = "LM_COMPANY";
    /**
     * Parameter: LogicMonitor auth as json.
     */
    public static final String PARAMETER_LM_AUTH = "LM_AUTH";
    /**
     * Parameter: LogicMonitor access ID.
     */
    public static final String PARAMETER_ACCESS_ID = "LM_ACCESS_ID";
    /**
     * Parameter: LogicMonitor access key.
     */
    public static final String PARAMETER_ACCESS_KEY = "LM_ACCESS_KEY";
    /**
     * Parameter: LogicMonitor bearer token.
     */
    public static final String PARAMETER_BEARER_TOKEN = "LM_BEARER_TOKEN";

    /**
     * Parameter: connection timeout in milliseconds (default 10000).
     */
    public static final String PARAMETER_CONNECT_TIMEOUT = "LogApiClientConnectTimeout";
    /**
     * Parameter: read timeout in milliseconds (default 10000).
     */
    public static final String PARAMETER_READ_TIMEOUT = "LogApiClientReadTimeout";
    /**
     * Parameter: HTTP client debugging.
     */
    public static final String PARAMETER_DEBUGGING = "LogApiClientDebugging";
    /**
     * Parameter: Regex to scrub text from logs.
     */
    public static final String PARAMETER_REGEX_SCRUB = "LogRegexScrub";
    /**
     * Parameter: Azure Application Client ID
     */
    public static final String PARAMETER_AZURE_CLIENT_ID = "AzureClientID";
    /**
     * Parameter: Azure Azure Account Name
     */
    public static final String PARAMETER_AZURE_ACCOUNT_NAME = "AzureAccountName";
    /**
     * Parameter: comma separated metadata keys to look in azure events and then add to metadata
     */
    public static final String PARAMETER_INCLUDE_METADATA_KEYS = "Include_Metadata_keys";
    /**
     * Transforms Azure log events into log entries.
     */
    private static LogEventAdapter adapter;

    private static final Gson GSON = new GsonBuilder().create();
    public final Configuration conf = createDataSdkConfig();


    protected static Configuration createDataSdkConfig() {
        String company = System.getenv(PARAMETER_COMPANY_NAME);
        try {
            JsonObject authConf = GSON.fromJson(removeQuotesAndUnescape(System.getenv(PARAMETER_LM_AUTH)), JsonObject.class);
            String accessId = authConf.get(PARAMETER_ACCESS_ID).getAsString();
            String accessKey = authConf.get(PARAMETER_ACCESS_KEY).getAsString();
            String bearerToken = authConf.get(PARAMETER_BEARER_TOKEN).getAsString();

            if (StringUtils.isNoneBlank(accessKey, accessId)) {
                // configure with null bearer token
                log(Level.FINE, "Using LMv1 for authentication with Logicmonitor.");
                return new Configuration(company, accessId, accessKey, null);
            } else {
                // configure with just Bearer token
                log(Level.FINE, "Using bearer token for authentication with Logicmonitor.");
                return new Configuration(company, null, null, bearerToken);
            }
        } catch (Exception e) {
            log(Level.SEVERE,
                "Unable to configure LM Data SDK config with ENV var LM_AUTH. Log Ingestion will be interrupted. Error : "
                    + e.getMessage());
            return new Configuration();

        }
    }

    public void setResponseInterface(final ExecutionContext context) {
        this.responseInterface = new LogIngestResponse(context, context.getLogger());
    }

    public LogIngestResponse responseInterface;

    /**
     * Gets the log adapter instance (initializes it when needed).
     *
     * @return LogEventAdapter instance
     */
    protected synchronized static LogEventAdapter getAdapter() {
        // The initialization must be lazy for the testing
        // - the test classes must set the environmental variables first.
        if (adapter == null) {
            adapter = configureAdapter();
        }
        return adapter;
    }

    /**
     * Configures the log adapter using the environment variables.
     *
     * @return LogEventAdapter instance
     */
    protected static LogEventAdapter configureAdapter() {
        return new LogEventAdapter(System.getenv(PARAMETER_REGEX_SCRUB),
            System.getenv(PARAMETER_AZURE_CLIENT_ID),
            System.getenv(PARAMETER_AZURE_ACCOUNT_NAME),
            System.getenv(PARAMETER_INCLUDE_METADATA_KEYS));
    }

    public Logs configureLogs() {
        return new Logs(conf, 5, true, responseInterface);
    }

    /**
     * Reads an environment variable and sets using the specified consumer when not null nor empty.
     *
     * @param <T> type of the variable
     * @param name name of the variable
     * @param mapper function mapping String to the desired type
     * @param setter consumer setting the property
     */
    private static <T> void setProperty(String name, Function<String, T> mapper,
        Consumer<T> setter) {
        Optional.ofNullable(System.getenv(name))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(mapper)
            .ifPresent(setter);
    }

    /**
     * The main method of the Azure Log Forwarder, triggered by events consumed from the configured
     * Event Hub.
     *
     * @param logEvents list of JSON strings containing Azure events
     * @param context execution context
     */
    @FunctionName("LogForwarder")
    public void forward(
        @EventHubTrigger(name = "logEvents", eventHubName = "log-hub",
            dataType = "string", cardinality = Cardinality.MANY,
            connection = "LogsEventHubConnectionString") List<String> logEvents,
        final ExecutionContext context
    ) {
        setResponseInterface(context);
        Logs logs = configureLogs();
        List<LogEntry> logEntries = processEvents(logEvents);
        if (logEntries.isEmpty()) {
            log(context, Level.INFO, () -> "No entries to send");
            return;
        }

        log(Level.FINE, "Sending " + logEntries.size() +
            " log entries for devices " + getResourceIds(logEntries));
        for (LogEntry logEntry : logEntries) {
            try {
                Optional<ApiResponse> response = logs.sendLogs(logEntry.getMessage(),
                    logEntry.getLmResourceId(), logEntry.getMetadata(), logEntry.getTimestamp());
                if (response != null && response.isPresent()) {
                    logResponse(context, response.get());
                }
            } catch (final Exception e) {
                log(context, Level.SEVERE,
                    () -> "Exception occurred while processing the request: " + e.getMessage());
            }
        }

    }

    /**
     * Processes the received events and produces log events.
     *
     * @param logEvents list of JSON strings containing Azure events
     * @return the log entries
     */
    protected static List<LogEntry> processEvents(List<String> logEvents) {
        List<LogEntry> validLogEntries = new ArrayList<>();
        try {
             logEvents.stream()
                .map(getAdapter())
                .flatMap(List::stream)
                 .forEach(validLogEntries::add);
        } catch (JsonSyntaxException e) {
            log(Level.INFO, "Error while processing Json of events : " + e.getMessage() + " :: " +logEvents);
        }
        return validLogEntries;
    }

    /**
     * Gets unique resource IDs.
     *
     * @param logEntries log entries
     * @return set of resource IDs
     */
    protected static Set<String> getResourceIds(List<LogEntry> logEntries) {
        return logEntries.stream()
            .map(LogEntry::getLmResourceId)
            .map((props) -> {
                if (props.containsKey(LogEventAdapter.LM_RESOURCE_PROPERTY)) {
                    return props.get(LogEventAdapter.LM_RESOURCE_PROPERTY);
                } else {
                    return props.get(LogEventAdapter.LM_CLIENT_ID);
                }
            })
            .collect(Collectors.toSet());
    }



    /**
     * Logs a response received from LogicMonitor.
     *
     * @param context execution context
     * @param response the response to log
     */
    private static void logResponse(final ExecutionContext context,
        ApiResponse<?> response) {
        log(context, Level.INFO,
            () -> String.format("Received: status = %d ",
                response.getStatusCode()));
        log(context, Level.INFO,
            () -> "Response body: " + response.getData());
    }

    /**
     * gets the gradle 'Implementation-Version'.
     *
     * @return the project version
     */
    private static String getBuildVersion() {
        return LogEventForwarder.class.getPackage().getImplementationVersion();
    }

    /**
     * gets the gradle 'Implementation-Title'.
     *
     * @return the project name
     */
    private static String getBuildName() {
        return LogEventForwarder.class.getPackage().getImplementationTitle();
    }

    /**
     * generates user-agent as <buildname>/<buildversion>.
     *
     * @return the user-agent
     */
    public static String getUserAgent() {
        return getBuildName() + "/" + getBuildVersion();
    }


    class LogIngestResponse implements ApiCallback {

        public static final String JSON_PROPERTY_SUCCESS = "success";
        private Boolean success;

        public ExecutionContext getContext() {
            return context;
        }

        ExecutionContext context;

        public LogIngestResponse(final ExecutionContext context, Logger logger) {
            this.context = context;
        }

        public LogIngestResponse success(Boolean success) {
            this.success = success;
            return this;
        }

        @Override
        public void onFailure(org.openapitools.client.ApiException e, int i, Map map) {
            log(Level.SEVERE,
                String.format("[%s][%s] Failed to ingest logs to Logicmonitor. Error = %s",
                    this.getContext().getFunctionName(), this.getContext().getInvocationId(),
                    e.getMessage()));
        }

        @Override
        public void onSuccess(Object o, int i, Map map) {
            log(Level.INFO, String.format(
                "[%s][%s] Successfully ingested logs to Logicmonitor. x-request-id=%s",
                this.getContext().getFunctionName(), this.getContext().getInvocationId(),
                map.get("x-request-id")));
        }

        @Override
        public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {
        }

        @Override
        public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {
        }
    }
}
