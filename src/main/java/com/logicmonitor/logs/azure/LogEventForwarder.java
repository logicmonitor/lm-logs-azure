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
     * LM Data SDK batch flush interval in seconds (passed to {@code new Logs(conf, interval, batch)}).
     */
    private static final int LM_BATCH_INTERVAL_SEC = 5;
    /**
     * When true, {@link Logs#sendLogs} queues entries and returns empty immediately; ingest
     * completion is tracked via {@link LogIngestResponse} and {@link #waitForBatchIngest}.
     */
    private static final boolean LM_BATCH_ENABLED = true;
    /**
     * Overall per-entry send budget (connect + read), applied via {@link #sendLogsWithTimeout}
     * and as the HTTP portion of {@link #waitForBatchIngest}.
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
        // batch=true: sendLogs queues immediately; background threads merge/flush. Completion is
        // tracked via ApiCallback and waitForBatchIngest before checkpointing.
        // batch=false: sendLogs blocks until HTTP completes (sync path).
        synchronized (LOGS_LOCK) {
            if (logs == null) {
                final int connectTimeoutMs = resolveTimeoutMs(
                    PARAMETER_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT_MS);
                final int readTimeoutMs = resolveTimeoutMs(
                    PARAMETER_READ_TIMEOUT, DEFAULT_READ_TIMEOUT_MS);
                final int callTimeoutMs = connectTimeoutMs + readTimeoutMs;
                log(context, Level.FINE,
                    () -> "Initializing LM Logs SDK client (batch=" + LM_BATCH_ENABLED
                        + ", intervalSec=" + LM_BATCH_INTERVAL_SEC + "),"
                        + " connectTimeoutMs=" + connectTimeoutMs
                        + ", readTimeoutMs=" + readTimeoutMs
                        + ", callTimeoutMs=" + callTimeoutMs + ")");
                logs = new Logs(conf, LM_BATCH_INTERVAL_SEC, LM_BATCH_ENABLED,
                    responseInterface);
                sendTimeoutMs = callTimeoutMs;
                applyApiClientTimeouts(logs, connectTimeoutMs, readTimeoutMs, callTimeoutMs,
                    context);
                log(context, Level.FINE, () -> "LM Logs SDK client initialized");
            } else {
                log(context, Level.FINE, () -> "Reusing existing LM Logs SDK client");
            }
            // Per-invocation callback so batch results attach to the current tracker/context.
            logs.setApiCallback(responseInterface);
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
     * After queuing batch-mode sends, waits until the SDK drains its queues and reports ingest
     * outcome via {@link LogIngestResponse}.
     */
    protected void waitForBatchIngest(Logs logsClient, LogIngestResponse ingestResponse,
        int entryCount, int timeoutMs) throws Exception {
        ingestResponse.getBatchTracker().begin(entryCount);
        log(ingestResponse.getContext(), Level.FINE,
            () -> "Waiting for batch ingest of " + entryCount + " entr"
                + (entryCount == 1 ? "y" : "ies") + " (timeoutMs=" + timeoutMs + ")");
        ingestResponse.getBatchTracker().awaitCompletion(logsClient, timeoutMs);
        BatchIngestTracker tracker = ingestResponse.getBatchTracker();
        if (tracker.hasFailure()) {
            ApiException failure = tracker.getLastFailure();
            String detail = failure != null ? failure.getMessage() : "unknown batch failure";
            throw new RuntimeException(
                "Batch ingest failed for LogicMonitor (" + detail + ")", failure);
        }
        log(ingestResponse.getContext(), Level.FINE,
            () -> "Batch ingest complete: callbacks=" + tracker.getSuccessCallbacks()
                + ", queuedEntries=" + entryCount);
    }

    /**
     * Batch flush wait budget: SDK interval plus HTTP connect+read budget per send.
     */
    protected static int batchIngestTimeoutMs(int sendTimeoutMs) {
        return (LM_BATCH_INTERVAL_SEC * 1000) + sendTimeoutMs;
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

        final boolean batchMode = logsClient.isBatch();
        SendResult sendResult;
        if (batchMode) {
            synchronized (LOGS_LOCK) {
                logsClient.setApiCallback(responseInterface);
                sendResult = sendLogEntries(logsClient, logEntries, context, true);
                if (sendResult.queuedCount > 0 && sendResult.errorCount == 0) {
                    try {
                        waitForBatchIngest(logsClient, responseInterface,
                            sendResult.queuedCount, batchIngestTimeoutMs(sendTimeoutMs));
                        sendResult.successCount += sendResult.queuedCount;
                    } catch (Exception e) {
                        sendResult.errorCount += sendResult.queuedCount;
                        sendResult.lastException = e;
                        log(context, Level.SEVERE,
                            () -> "Batch ingest did not complete: " + e.getMessage());
                    }
                }
            }
        } else {
            sendResult = sendLogEntries(logsClient, logEntries, context, false);
        }

        final int successCount = sendResult.successCount;
        final int queuedCount = sendResult.queuedCount;
        final int emptyResponseCount = sendResult.emptyResponseCount;
        final int errorCount = sendResult.errorCount;
        final Exception lastSendException = sendResult.lastException;

        final int sentOk = successCount;
        final int sentQueued = queuedCount;
        final int sentEmpty = emptyResponseCount;
        final int sentFailed = errorCount;
        log(context, Level.INFO,
            () -> "Invocation finished: entries=" + logEntries.size()
                + ", httpResponses=" + sentOk
                + (batchMode ? ", queued=" + sentQueued : ", emptyResponses=" + sentEmpty)
                + ", errors=" + sentFailed
                + ", elapsedMs=" + (System.currentTimeMillis() - invocationStartMs));

        // Fail the invocation on any incomplete ingest so the Event Hub trigger does not
        // advance the offset checkpoint for a batch that was not fully delivered to LM.
        if (errorCount > 0 || emptyResponseCount > 0) {
            String message = String.format(
                "Failed to ingest logs to LogicMonitor (httpOk=%d, %s=%d, errors=%d, entries=%d). "
                    + "Failing Function invocation to prevent Event Hub checkpoint advance.",
                successCount,
                batchMode ? "queuedNotConfirmed" : "emptyResponses",
                batchMode ? Math.max(0, queuedCount - successCount) : emptyResponseCount,
                errorCount, logEntries.size());
            log(context, Level.SEVERE, () -> message);
            RuntimeException failure = new RuntimeException(message);
            if (lastSendException != null) {
                failure.initCause(lastSendException);
            }
            throw failure;
        }
    }

    /**
     * Sends parsed log entries to LogicMonitor.
     *
     * @return counts and the last send exception, if any
     */
    protected SendResult sendLogEntries(Logs logsClient, List<LogEntry> logEntries,
        final ExecutionContext context, boolean batchMode) {
        SendResult result = new SendResult();
        for (int i = 0; i < logEntries.size(); i++) {
            LogEntry logEntry = logEntries.get(i);
            final int entryIndex = i + 1;
            final int totalEntries = logEntries.size();
            log(context, Level.FINE,
                () -> "Calling LM sendLogs " + entryIndex + "/" + totalEntries
                    + " (resourceIds=" + logEntry.getLmResourceId()
                    + (batchMode ? ", batch=true" : "") + ")");
            long sendStartMs = System.currentTimeMillis();
            try {
                Optional<ApiResponse> response;
                if (batchMode) {
                    response = logsClient.sendLogs(
                        logEntry.getMessage(), logEntry.getLmResourceId(),
                        logEntry.getMetadata(), logEntry.getTimestamp());
                } else {
                    response = sendLogsWithTimeout(logsClient, logEntry, sendTimeoutMs);
                }
                long sendElapsedMs = System.currentTimeMillis() - sendStartMs;
                if (response != null && response.isPresent()) {
                    int statusCode = response.get().getStatusCode();
                    if (statusCode >= 200 && statusCode < 300) {
                        result.successCount++;
                        log(context, Level.FINE,
                            () -> "LM sendLogs " + entryIndex + "/" + totalEntries
                                + " returned HTTP " + statusCode + " in " + sendElapsedMs + "ms");
                        logResponse(context, response.get());
                    } else {
                        result.errorCount++;
                        final int failedStatus = statusCode;
                        log(context, Level.SEVERE,
                            () -> "LM sendLogs " + entryIndex + "/" + totalEntries
                                + " returned HTTP " + failedStatus + " in " + sendElapsedMs
                                + "ms (treating as failure to avoid Event Hub checkpoint)");
                    }
                } else if (batchMode && (response == null || !response.isPresent())) {
                    result.queuedCount++;
                    log(context, Level.FINE,
                        () -> "LM sendLogs " + entryIndex + "/" + totalEntries
                            + " queued for batch ingest in " + sendElapsedMs + "ms");
                } else {
                    result.emptyResponseCount++;
                    log(context, Level.SEVERE,
                        () -> "LM sendLogs " + entryIndex + "/" + totalEntries
                            + " returned no response in " + sendElapsedMs
                            + "ms (treating as failure to avoid Event Hub checkpoint)");
                }
            } catch (final Exception e) {
                result.errorCount++;
                result.lastException = e;
                long sendElapsedMs = System.currentTimeMillis() - sendStartMs;
                log(context, Level.SEVERE,
                    () -> "Exception on LM sendLogs " + entryIndex + "/" + totalEntries
                        + " after " + sendElapsedMs + "ms: " + e.getMessage());
            }
        }
        return result;
    }

    static class SendResult {
        int successCount;
        int queuedCount;
        int emptyResponseCount;
        int errorCount;
        Exception lastException;
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
        private final BatchIngestTracker batchTracker = new BatchIngestTracker();

        public ExecutionContext getContext() {
            return context;
        }

        ExecutionContext context;

        public LogIngestResponse(final ExecutionContext context, Logger logger) {
            this.context = context;
        }

        public BatchIngestTracker getBatchTracker() {
            return batchTracker;
        }

        public LogIngestResponse success(Boolean success) {
            this.success = success;
            return this;
        }

        @Override
        public void onFailure(ApiException e, int statusCode, Map responseHeaders) {
            batchTracker.recordFailure(e, statusCode);
            log(this.getContext(), Level.SEVERE,
                () -> "Failed to ingest logs to Logicmonitor (HTTP " + statusCode
                    + "). Error = " + e.getMessage());
        }

        @Override
        public void onSuccess(Object responseBody, int statusCode, Map responseHeaders) {
            batchTracker.recordSuccess(statusCode);
            log(this.getContext(), Level.INFO,
                () -> "Successfully ingested logs to Logicmonitor (HTTP " + statusCode
                    + ", x-request-id=" + responseHeaders.get("x-request-id") + ")");
        }

        @Override
        public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {
        }

        @Override
        public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {
        }
    }

    /**
     * Tracks batch-mode ingest completion reported by the LM Data SDK {@link ApiCallback}.
     */
    static class BatchIngestTracker {

        private static final long QUIET_PERIOD_MS = 250;

        private int expectedEntries;
        private int successCallbacks;
        private int failureCallbacks;
        private volatile ApiException lastFailure;
        private volatile long lastCallbackMs;

        void begin(int expectedEntries) {
            this.expectedEntries = expectedEntries;
            this.successCallbacks = 0;
            this.failureCallbacks = 0;
            this.lastFailure = null;
            this.lastCallbackMs = 0;
        }

        void recordSuccess(int statusCode) {
            if (statusCode < 200 || statusCode >= 300) {
                recordFailure(new ApiException(statusCode, "Unexpected HTTP status " + statusCode),
                    statusCode);
                return;
            }
            successCallbacks++;
            lastCallbackMs = System.currentTimeMillis();
        }

        void recordFailure(ApiException e, int statusCode) {
            failureCallbacks++;
            lastFailure = e;
            lastCallbackMs = System.currentTimeMillis();
        }

        boolean hasFailure() {
            return failureCallbacks > 0;
        }

        ApiException getLastFailure() {
            return lastFailure;
        }

        int getSuccessCallbacks() {
            return successCallbacks;
        }

        void awaitCompletion(Logs logsClient, long timeoutMs)
            throws InterruptedException, TimeoutException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (hasFailure()) {
                    return;
                }
                if (isDrained(logsClient) && successCallbacks > 0
                    && System.currentTimeMillis() - lastCallbackMs >= QUIET_PERIOD_MS) {
                    return;
                }
                Thread.sleep(50);
            }
            if (hasFailure()) {
                return;
            }
            if (successCallbacks == 0 || !isDrained(logsClient)) {
                throw new TimeoutException(
                    "Batch ingest of " + expectedEntries + " entries did not complete within "
                        + timeoutMs + "ms (successCallbacks=" + successCallbacks
                        + ", drained=" + isDrained(logsClient) + ")");
            }
        }

        private static boolean isDrained(Logs logsClient) {
            return logsClient.getRawRequest().isEmpty()
                && logsClient.getLogPayloadCache().isEmpty();
        }
    }
}
