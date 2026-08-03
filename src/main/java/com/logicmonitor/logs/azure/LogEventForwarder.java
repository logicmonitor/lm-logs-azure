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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
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
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;
import org.openapitools.client.ApiCallback;
import org.openapitools.client.ApiClient;
import org.openapitools.client.ApiException;
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
 * <li>{@value #PARAMETER_EVENT_HUB_NAME} Event Hub name
 *     (default {@value #DEFAULT_EVENT_HUB_NAME})
 * <li>{@value #PARAMETER_EVENT_HUB_CONSUMER_GROUP} Event Hub consumer group
 *     (default {@value #DEFAULT_EVENT_HUB_CONSUMER_GROUP})
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
     * Parameter: connection timeout in milliseconds (default {@value #DEFAULT_CONNECT_TIMEOUT_MS}).
     */
    public static final String PARAMETER_CONNECT_TIMEOUT = "LogApiClientConnectTimeout";
    /**
     * Parameter: read timeout in milliseconds (default {@value #DEFAULT_READ_TIMEOUT_MS}).
     */
    public static final String PARAMETER_READ_TIMEOUT = "LogApiClientReadTimeout";
    /**
     * Parameter: HTTP client debugging.
     */
    public static final String PARAMETER_DEBUGGING = "LogApiClientDebugging";
    /**
     * Default HTTP connect timeout (ms) when {@value #PARAMETER_CONNECT_TIMEOUT} is unset.
     */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;
    /**
     * Default HTTP read timeout (ms) when {@value #PARAMETER_READ_TIMEOUT} is unset.
     */
    public static final int DEFAULT_READ_TIMEOUT_MS = 10000;
    /**
     * Guards lazy init of the shared {@link Logs} client.
     */
    private static final Object LOGS_LOCK = new Object();
    /**
     * Overall per-entry send budget (connect + read), applied via {@link #sendLogsWithTimeout}.
     */
    private static volatile int sendTimeoutMs =
        DEFAULT_CONNECT_TIMEOUT_MS + DEFAULT_READ_TIMEOUT_MS;
    /**
     * Executes {@code sendLogs} calls so a hard overall timeout can be enforced.
     * Needed because lm-data-sdk's sync path creates a fresh ApiClient per request and
     * ignores timeouts set on the shared Logs instance.
     */
    private static final ExecutorService SEND_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lm-logs-send");
        t.setDaemon(true);
        return t;
    });
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
     * Parameter: Event Hub name. Resolved from function.json binding
     * ({@code %eventHubName%}); defaults to {@value #DEFAULT_EVENT_HUB_NAME}.
     */
    public static final String PARAMETER_EVENT_HUB_NAME = "eventHubName";
    /**
     * Default Event Hub name used when {@value #PARAMETER_EVENT_HUB_NAME} is not configured.
     */
    public static final String DEFAULT_EVENT_HUB_NAME = "log-hub";
    /**
     * Parameter: Event Hub consumer group. Resolved from function.json binding
     * ({@code %eventHubConsumerGroup%}); defaults to {@value #DEFAULT_EVENT_HUB_CONSUMER_GROUP}.
     */
    public static final String PARAMETER_EVENT_HUB_CONSUMER_GROUP = "eventHubConsumerGroup";
    /**
     * Default Event Hub consumer group used when {@value #PARAMETER_EVENT_HUB_CONSUMER_GROUP}
     * is not configured.
     */
    public static final String DEFAULT_EVENT_HUB_CONSUMER_GROUP = "$Default";
    /**
     * Transforms Azure log events into log entries.
     */


    /**
     * Parameter: domain in the target URL '{company}.{domainName}'.
     */
    public static final String PARAMETER_DOMAIN_NAME = "LM_DOMAIN_NAME";
    private static LogEventAdapter adapter;

    private static final Gson GSON = new GsonBuilder().registerTypeAdapter(LogEventProperties.class, new LogEventPropertiesDeserializer())
            .create();

    public final Configuration conf = createDataSdkConfig();

    private static Logs logs;

    protected static Configuration createDataSdkConfig() {
        String company = System.getenv(PARAMETER_COMPANY_NAME);
        String domainName = System.getenv(PARAMETER_DOMAIN_NAME);
        try {
            JsonObject authConf = GSON.fromJson(removeQuotesAndUnescape(System.getenv(PARAMETER_LM_AUTH)), JsonObject.class);
            String accessId = authConf.get(PARAMETER_ACCESS_ID).getAsString();
            String accessKey = authConf.get(PARAMETER_ACCESS_KEY).getAsString();
            String bearerToken = authConf.get(PARAMETER_BEARER_TOKEN).getAsString();

            if (StringUtils.isNoneBlank(accessKey, accessId)) {
                // configure with null bearer token
                log(Level.FINE, "Using LMv1 for authentication with Logicmonitor.");
                return new Configuration(company, accessId, accessKey, null, domainName);
            } else {
                // configure with just Bearer token
                log(Level.FINE, "Using bearer token for authentication with Logicmonitor.");
                return new Configuration(company, null, null, bearerToken, domainName);
            }
        } catch (IllegalArgumentException e) {
            log(Level.SEVERE,
                "Unable to configure LM Data SDK config with ENV var LM_AUTH. Log Ingestion will be interrupted. Error : "
                    + e.getMessage());
            // Rethrow so the Function fails and Event Hub offset is not checkpointed.
            throw e;
        } catch (Exception e) {
            log(Level.SEVERE,
                "Unable to configure LM Data SDK config with ENV var LM_AUTH. Log Ingestion will be interrupted. Error : "
                    + e.getMessage());
            throw new IllegalArgumentException(
                "Unable to configure LM Data SDK with ENV var LM_AUTH: " + e.getMessage(), e);
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

    public Logs configureLogs(final ExecutionContext context) {
        // batch=false: send synchronously on the Function thread.
        // batch=true uses background merge/request threads that can hold SDK locks
        synchronized (LOGS_LOCK) {
            if (logs == null) {
                final int connectTimeoutMs = resolveTimeoutMs(
                    PARAMETER_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT_MS);
                final int readTimeoutMs = resolveTimeoutMs(
                    PARAMETER_READ_TIMEOUT, DEFAULT_READ_TIMEOUT_MS);
                final int callTimeoutMs = connectTimeoutMs + readTimeoutMs;
                log(context, Level.FINE,
                    () -> "Initializing LM Logs SDK client,"
                        + " connectTimeoutMs=" + connectTimeoutMs
                        + ", readTimeoutMs=" + readTimeoutMs
                        + ", callTimeoutMs=" + callTimeoutMs + ")");
                logs = new Logs(conf, 5, true, responseInterface);
                sendTimeoutMs = callTimeoutMs;
                applyApiClientTimeouts(logs, connectTimeoutMs, readTimeoutMs, callTimeoutMs,
                    context);
                log(context, Level.FINE, () -> "LM Logs SDK client initialized");
            } else {
                log(context, Level.FINE, () -> "Reusing existing LM Logs SDK client");
            }
            return logs;
        }
    }

    /**
     * Builds an {@link ApiClient} with connect/read/write/call timeouts and installs it on the
     * {@link Logs} instance and as the OpenAPI default client.
     * <p>
     * Note: lm-data-sdk's {@code batch=true} path still constructs a fresh client inside
     * {@code singleRequest}/{@code makeRequest}; overall send time is therefore also bounded
     * by {@link #sendLogsWithTimeout}.
     */
    protected void applyApiClientTimeouts(Logs logsClient, int connectTimeoutMs,
        int readTimeoutMs, int callTimeoutMs, final ExecutionContext context) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .build();

        ApiClient apiClient = new ApiClient(httpClient);
        apiClient.setBasePath(Configuration.setCompany());
        apiClient.setConnectTimeout(connectTimeoutMs);
        apiClient.setReadTimeout(readTimeoutMs);
        apiClient.setWriteTimeout(readTimeoutMs);
        setProperty(PARAMETER_DEBUGGING, Boolean::valueOf, enabled -> {
            apiClient.setDebugging(enabled);
            log(context, Level.FINE, () -> "LM API client debugging=" + enabled);
        });

        logsClient.setApiClient(apiClient);
        org.openapitools.client.Configuration.setDefaultApiClient(apiClient);
        log(context, Level.FINE,
            () -> "Applied LM API client timeouts: connect=" + apiClient.getConnectTimeout()
                + "ms, read=" + apiClient.getReadTimeout() + "ms, call=" + callTimeoutMs + "ms");
    }

    /**
     * Resolves a timeout setting from the environment, falling back to {@code defaultMs}.
     */
    protected static int resolveTimeoutMs(String envName, int defaultMs) {
        try {
            return Optional.ofNullable(System.getenv(envName))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::valueOf)
                .filter(value -> value > 0)
                .orElse(defaultMs);
        } catch (NumberFormatException e) {
            log(Level.WARNING,
                "Invalid " + envName + " value; using default " + defaultMs + "ms");
            return defaultMs;
        }
    }

    /**
     * Invokes {@link Logs#sendLogs} with a hard overall timeout so a stalled SDK HTTP call
     * cannot hold the Function until the host {@code functionTimeout} (30 minutes).
     */
    protected Optional<ApiResponse> sendLogsWithTimeout(Logs logsClient, LogEntry logEntry,
        int timeoutMs) throws Exception {
        Future<Optional<ApiResponse>> future = SEND_EXECUTOR.submit(
            () -> logsClient.sendLogs(logEntry.getMessage(), logEntry.getLmResourceId(),
                logEntry.getMetadata(), logEntry.getTimestamp()));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException(
                "LM sendLogs exceeded " + timeoutMs + "ms (connect+read timeout budget)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
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
        @EventHubTrigger(name = "logEvents", eventHubName = "%EventHubName%",
            consumerGroup = "%EventHubConsumerGroup%",
            dataType = "string", cardinality = Cardinality.MANY,
            connection = "LogsEventHubConnectionString") List<String> logEvents,
        final ExecutionContext context
    ) {
        final long invocationStartMs = System.currentTimeMillis();
        final int rawEventCount = logEvents == null ? 0 : logEvents.size();
        log(context, Level.INFO,
            () -> "Invocation started: received " + rawEventCount + " Event Hub message(s)");

        setResponseInterface(context);

        log(context, Level.INFO, () -> "Configuring LM Logs SDK");
        Logs logsClient = configureLogs(context);

        log(context, Level.INFO, () -> "Parsing Event Hub messages into log entries");
        List<LogEntry> logEntries = processEvents(logEvents, context);
        log(context, Level.INFO,
            () -> "Parsing complete: " + logEntries.size() + " log entr"
                + (logEntries.size() == 1 ? "y" : "ies") + " from " + rawEventCount
                + " Event Hub message(s)");

        if (logEntries.isEmpty()) {
            log(context, Level.INFO, () -> "No entries to send; invocation complete");
            return;
        }

        log(context, Level.INFO, () -> "Sending " + logEntries.size() +
            " log entries for devices " + getResourceIds(logEntries));

        int successCount = 0;
        int emptyResponseCount = 0;
        int errorCount = 0;
        Exception lastSendException = null;
        for (int i = 0; i < logEntries.size(); i++) {
            LogEntry logEntry = logEntries.get(i);
            final int entryIndex = i + 1;
            final int totalEntries = logEntries.size();
            log(context, Level.FINE,
                () -> "Calling LM sendLogs " + entryIndex + "/" + totalEntries
                    + " (resourceIds=" + logEntry.getLmResourceId() + ")");
            long sendStartMs = System.currentTimeMillis();
            try {
                Optional<ApiResponse> response =
                    sendLogsWithTimeout(logsClient, logEntry, sendTimeoutMs);
                long sendElapsedMs = System.currentTimeMillis() - sendStartMs;
                if (response != null && response.isPresent()) {
                    int statusCode = response.get().getStatusCode();
                    if (statusCode >= 200 && statusCode < 300) {
                        successCount++;
                        log(context, Level.FINE,
                            () -> "LM sendLogs " + entryIndex + "/" + totalEntries
                                + " returned HTTP " + statusCode + " in " + sendElapsedMs + "ms");
                        logResponse(context, response.get());
                    } else {
                        errorCount++;
                        final int failedStatus = statusCode;
                        log(context, Level.SEVERE,
                            () -> "LM sendLogs " + entryIndex + "/" + totalEntries
                                + " returned HTTP " + failedStatus + " in " + sendElapsedMs
                                + "ms (treating as failure to avoid Event Hub checkpoint)");
                    }
                } else {
                    emptyResponseCount++;
                    log(context, Level.SEVERE,
                        () -> "LM sendLogs " + entryIndex + "/" + totalEntries
                            + " returned no response in " + sendElapsedMs
                            + "ms (treating as failure to avoid Event Hub checkpoint)");
                }
            } catch (final Exception e) {
                errorCount++;
                lastSendException = e;
                long sendElapsedMs = System.currentTimeMillis() - sendStartMs;
                log(context, Level.SEVERE,
                    () -> "Exception on LM sendLogs " + entryIndex + "/" + totalEntries
                        + " after " + sendElapsedMs + "ms: " + e.getMessage());
            }
        }

        final int sentOk = successCount;
        final int sentQueued = emptyResponseCount;
        final int sentFailed = errorCount;
        log(context, Level.INFO,
            () -> "Invocation finished: entries=" + logEntries.size()
                + ", httpResponses=" + sentOk
                + ", emptyResponses=" + sentQueued
                + ", errors=" + sentFailed
                + ", elapsedMs=" + (System.currentTimeMillis() - invocationStartMs));

        // Fail the invocation on any incomplete ingest so the Event Hub trigger does not
        // advance the offset checkpoint for a batch that was not fully delivered to LM.
        if (errorCount > 0 || emptyResponseCount > 0) {
            String message = String.format(
                "Failed to ingest logs to LogicMonitor (httpOk=%d, emptyResponses=%d, errors=%d, entries=%d). "
                    + "Failing Function invocation to prevent Event Hub checkpoint advance.",
                successCount, emptyResponseCount, errorCount, logEntries.size());
            log(context, Level.SEVERE, () -> message);
            RuntimeException failure = new RuntimeException(message);
            if (lastSendException != null) {
                failure.initCause(lastSendException);
            }
            throw failure;
        }
    }

    /**
     * Processes the received events and produces log events.
     *
     * @param logEvents list of JSON strings containing Azure events
     * @return the log entries
     */
    protected static List<LogEntry> processEvents(List<String> logEvents) {
        return processEvents(logEvents, null);
    }

    protected static List<LogEntry> processEvents(List<String> logEvents,
        final ExecutionContext context) {
        if (logEvents == null || logEvents.isEmpty()) {
            return new ArrayList<>();
        }
        List<LogEntry> validLogEntries = new ArrayList<>();
        try {
            logEvents.stream()
                .map(getAdapter())
                .flatMap(List::stream)
                .forEach(validLogEntries::add);
        } catch (JsonSyntaxException e) {
            String message =
                "Error while processing Json of events : " + e.getMessage() + " :: " + logEvents;
            if (context != null) {
                log(context, Level.SEVERE, () -> message);
            } else {
                log(Level.SEVERE, message);
            }
            // Rethrow so the Function fails and Event Hub offset is not checkpointed.
            throw e;
        } catch (RuntimeException e) {
            String message =
                "Error while processing events : " + e.getMessage() + " :: " + logEvents;
            if (context != null) {
                log(context, Level.SEVERE, () -> message);
            } else {
                log(Level.SEVERE, message);
            }
            throw e;
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
        public void onFailure(ApiException e, int i, Map map) {
            log(this.getContext(), Level.SEVERE,
                () -> "Failed to ingest logs to Logicmonitor. Error = " + e.getMessage());
            // With batch=false, sendLogs should surface failures synchronously; this callback
            // is retained for SDK compatibility. Synchronous path failures are handled in forward().
        }

        @Override
        public void onSuccess(Object o, int i, Map map) {
            log(this.getContext(), Level.INFO,
                () -> "Successfully ingested logs to Logicmonitor. x-request-id="
                    + map.get("x-request-id"));
        }

        @Override
        public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {
        }

        @Override
        public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {
        }
    }
}
