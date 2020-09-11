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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import com.logicmonitor.logs.LMLogsApi;
import com.logicmonitor.logs.LMLogsApiException;
import com.logicmonitor.logs.LMLogsApiResponse;
import com.logicmonitor.logs.model.LogEntry;
import com.logicmonitor.logs.model.LogResponse;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.Cardinality;
import com.microsoft.azure.functions.annotation.EventHubTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;

/**
 * Azure Function forwarding Azure logs to LogicMonitor endpoint.<br>
 * It is parametrized using the following environment variables:
 * <ul>
 * <li>{@value #PARAMETER_COMPANY_NAME} company in the target URL '{company}.logicmonitor.com'
 * <li>{@value #PARAMETER_ACCESS_ID} LogicMonitor access ID
 * <li>{@value #PARAMETER_ACCESS_KEY} LogicMonitor access key
 * <li>{@value #PARAMETER_CONNECT_TIMEOUT} Connection timeout in milliseconds (default 10000)
 * <li>{@value #PARAMETER_READ_TIMEOUT} Read timeout in milliseconds (default 10000)
 * <li>{@value #PARAMETER_DEBUGGING} HTTP client debugging
 * <li>{@value #PARAMETER_REGEX_SCRUB} Regex to scrub text from logs
 * </ul>
 */
public class LogEventForwarder {
    /**
     * Parameter: company in the target URL '{company}.logicmonitor.com'.
     */
    public static final String PARAMETER_COMPANY_NAME = "LogicMonitorCompanyName";
    /**
     * Parameter: LogicMonitor access ID.
     */
    public static final String PARAMETER_ACCESS_ID = "LogicMonitorAccessId";
    /**
     * Parameter: LogicMonitor access key.
     */
    public static final String PARAMETER_ACCESS_KEY = "LogicMonitorAccessKey";
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
     * Transforms Azure log events into log entries.
     */
    private static LogEventAdapter adapter;
    /**
     * API for sending log requests.
     */
    private static LMLogsApi api;

    /**
     * Gets the log adapter instance (initializes it when needed).
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
     * @return LogEventAdapter instance
     */
    protected static LogEventAdapter configureAdapter() {
        return new LogEventAdapter(System.getenv(PARAMETER_REGEX_SCRUB));
    }

    /**
     * Gets the API instance (initializes it when needed).
     * @return LMLogsApi instance
     */
    protected synchronized static LMLogsApi getApi() {
        // The initialization must be lazy for the testing
        // - the test classes must set the environmental variables first.
        if (api == null) {
            api = configureApi();
        }
        return api;
    }

    /**
     * Configures API using the environment variables.
     * @return LMLogsApi instance
     */
    protected static LMLogsApi configureApi() {
        LMLogsApi.Builder builder = new LMLogsApi.Builder()
             .withCompany(System.getenv(PARAMETER_COMPANY_NAME))
             .withAccessId(System.getenv(PARAMETER_ACCESS_ID))
             .withAccessKey(System.getenv(PARAMETER_ACCESS_KEY));
        setProperty(PARAMETER_CONNECT_TIMEOUT, Integer::valueOf, builder::withConnectTimeout);
        setProperty(PARAMETER_READ_TIMEOUT, Integer::valueOf, builder::withReadTimeout);
        setProperty(PARAMETER_DEBUGGING, Boolean::valueOf, builder::withDebugging);
        return builder.build();
    }

    /**
     * Reads an environment variable and sets using the specified consumer
     * when not null nor empty.
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
     * The main method of the Azure Log Forwarder, triggered by events consumed
     * from the configured Event Hub.
     * @param logEvents list of JSON strings containing Azure events
     * @param context execution context
     */
    @FunctionName("LogForwarder")
    public void forward(
            @EventHubTrigger(name = "logEvents", eventHubName = "eventHub",
                    dataType = "string", cardinality = Cardinality.MANY,
                    connection = "LogsEventHubConnectionString") List<String> logEvents,
            final ExecutionContext context
    ) {
        List<LogEntry> logEntries = processEvents(logEvents);
        if (logEntries.isEmpty()) {
            log(context, Level.INFO, () -> "No entries to send");
            return;
        }

        log(context, Level.INFO, () -> "Sending " + logEntries.size() +
                " log entries for devices " + getResourceIds(logEntries));
        log(context, Level.FINEST, () -> "Request body: " + logEntries);
        try {
            LMLogsApiResponse<LogResponse> response = getApi().logIngestPostWithHttpInfo(logEntries);
            logResponse(context, response.getData().getSuccess(), response);
        } catch (LMLogsApiException e) {
            logResponse(context, false, e.getResponse());
        }
    }

    /**
     * Processes the received events and produces log events.
     * @param logEvents list of JSON strings containing Azure events
     * @return the log entries
     */
    protected static List<LogEntry> processEvents(List<String> logEvents) {
        return logEvents.stream()
            .map(getAdapter())
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    /**
     * Gets unique resource IDs.
     * @param logEntries log entries
     * @return set of resource IDs
     */
    protected static Set<String> getResourceIds(List<LogEntry> logEntries) {
        return logEntries.stream()
            .map(LogEntry::getLmResourceId)
            .map(props -> props.get(LogEventAdapter.LM_RESOURCE_PROPERTY))
            .collect(Collectors.toSet());
    }

    /**
     * Logs a message with function name and invocation ID.
     * @param context execution context
     * @param level logging level
     * @param msgSupplier produces the message to log
     */
    private static void log(final ExecutionContext context, Level level,
            Supplier<String> msgSupplier) {
        context.getLogger().log(level, () -> String.format("[%s][%s] %s",
                context.getFunctionName(), context.getInvocationId(), msgSupplier.get()));
    }

    /**
     * Logs a response received from LogicMonitor.
     * @param context execution context
     * @param success if the request was successful
     * @param response the response to log
     */
    private static void logResponse(final ExecutionContext context, boolean success,
            LMLogsApiResponse<?> response) {
        log(context, success ? Level.INFO : Level.WARNING,
                () -> String.format("Received: status = %d, id = %s",
                        response.getStatusCode(), response.getRequestId()));
        log(context, success ? Level.FINEST : Level.WARNING,
                () -> "Response body: " + response.getData());
    }

}
