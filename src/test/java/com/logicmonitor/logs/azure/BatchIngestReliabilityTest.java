package com.logicmonitor.logs.azure;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static com.github.stefanbirkner.systemlambda.SystemLambda.tapSystemOut;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.logicmonitor.sdk.data.Configuration;
import com.logicmonitor.sdk.data.api.Logs;
import com.logicmonitor.sdk.data.model.LogsInput;
import com.microsoft.azure.functions.ExecutionContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.openapitools.client.ApiCallback;
import org.openapitools.client.ApiException;

class BatchIngestReliabilityTest {

    @Test
    void callbackAllowsNullResponseHeaders() {
        LogEventForwarder.LogIngestResponse response =
            new LogEventForwarder.LogIngestResponse(context(), mock(Logger.class));
        response.getBatchTracker().begin(1);

        assertDoesNotThrow(() -> response.onSuccess(new Object(), 202, null));
        assertEquals(1, response.getBatchTracker().getSuccessCallbacks());
    }

    @Test
    void trackerObservesSuccessFromAnotherThread() throws Exception {
        LogEventForwarder.BatchIngestTracker tracker =
            new LogEventForwarder.BatchIngestTracker();
        Logs logs = drainedLogs();
        tracker.begin(1);

        Thread callback = new Thread(() -> tracker.recordSuccess(202));
        callback.start();
        tracker.awaitCompletion(logs, 1000);
        callback.join();

        assertEquals(1, tracker.getSuccessCallbacks());
    }

    @Test
    void trackerReturnsFailureWithoutWaitingForDrain() {
        LogEventForwarder.BatchIngestTracker tracker =
            new LogEventForwarder.BatchIngestTracker();
        Logs logs = mock(Logs.class);
        tracker.begin(1);
        tracker.recordFailure(new ApiException(500, "failed"), 500);

        assertDoesNotThrow(() -> tracker.awaitCompletion(logs, 1000));
        assertEquals(true, tracker.hasFailure());
    }

    @Test
    void trackerTimesOutWithoutCallback() {
        LogEventForwarder.BatchIngestTracker tracker =
            new LogEventForwarder.BatchIngestTracker();
        tracker.begin(1);

        assertThrows(TimeoutException.class,
            () -> tracker.awaitCompletion(drainedLogs(), 25));
    }

    @Test
    void reliableBatchFlushReportsOneSuccess() {
        RecordingCallback callback = new RecordingCallback();
        TestReliableLogs logs = new TestReliableLogs(callback,
            apiCallback -> apiCallback.onSuccess(new Object(), 202, null));

        logs.flushOne();

        assertEquals(1, callback.successes.get());
        assertEquals(0, callback.failures.get());
    }

    @Test
    void reliableBatchFlushConvertsMissingResponseToFailure() {
        RecordingCallback callback = new RecordingCallback();
        TestReliableLogs logs = new TestReliableLogs(callback, apiCallback -> {
        });

        logs.flushOne();

        assertEquals(0, callback.successes.get());
        assertEquals(1, callback.failures.get());
    }

    @Test
    void reliableBatchFlushDoesNotTreat207AsFullSuccess() {
        RecordingCallback callback = new RecordingCallback();
        TestReliableLogs logs = new TestReliableLogs(callback,
            apiCallback -> apiCallback.onSuccess(new Object(), 207, null));

        logs.flushOne();

        assertEquals(0, callback.successes.get());
        assertEquals(1, callback.failures.get());
    }

    @Test
    void reliableBatchFlushTimesOutAndSuppressesLateSuccess() throws Exception {
        RecordingCallback callback = new RecordingCallback();
        TestReliableLogs logs = new TestReliableLogs(callback, apiCallback -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            apiCallback.onSuccess(new Object(), 202, null);
        }, 25);

        logs.flushOne();
        Thread.sleep(50);

        assertEquals(0, callback.successes.get());
        assertEquals(1, callback.failures.get());
    }

    @Test
    void sendLogEntriesDistinguishesQueuedAndEmptyResponses() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_COMPANY_NAME, "company")
            .and(LogEventForwarder.PARAMETER_LM_AUTH,
                "{\"LM_ACCESS_ID\":\"id\",\"LM_ACCESS_KEY\":\"key\","
                    + "\"LM_BEARER_TOKEN\":\"\"}")
            .execute(() -> {
                LogEventForwarder forwarder = new LogEventForwarder();
                LogEntry entry = new LogEntry().message("message").timestamp(1L)
                    .lmResourceId(Collections.singletonMap("system.azure.resourceid", "resource"));
                Logs logs = mock(Logs.class);
                when(logs.sendLogs(entry.getMessage(), entry.getLmResourceId(),
                    entry.getMetadata(), entry.getTimestamp())).thenReturn(null);

                LogEventForwarder.SendResult queued =
                    forwarder.sendLogEntries(logs, List.of(entry), context(), true);
                assertEquals(1, queued.queuedCount);
                assertEquals(0, queued.emptyResponseCount);

                when(logs.sendLogs(entry.getMessage(), entry.getLmResourceId(),
                    entry.getMetadata(), entry.getTimestamp())).thenReturn(Optional.empty());
                LogEventForwarder.SendResult empty =
                    forwarder.sendLogEntries(logs, List.of(entry), context(), false);
                assertEquals(0, empty.queuedCount);
                assertEquals(1, empty.emptyResponseCount);
            });
    }

    @Test
    void invalidEventDoesNotBlockFollowingValidEvent() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_AZURE_CLIENT_ID, "testClientId")
            .execute(() -> {
                List<LogEntry> entries = LogEventForwarder.processEvents(List.of(
                    "\"not-an-event\"",
                    "{\"resourceId\":\"resource\",\"time\":\"2026-01-01T00:00:00Z\"}"));

                assertEquals(1, entries.size());
                assertEquals("resource",
                    entries.get(0).getLmResourceId().get(LogEventAdapter.LM_RESOURCE_PROPERTY));
            });
    }

    @Test
    void malformedEventIsSkipped() throws Exception {
        withEnvironmentVariable(LogEventForwarder.PARAMETER_AZURE_CLIENT_ID, "testClientId")
            .execute(() ->
                assertEquals(0, LogEventForwarder.processEvents(List.of("{not-json")).size()));
    }

    @Test
    void loggingDoesNotDuplicateMessagesToStdout() throws Exception {
        String stdout = tapSystemOut(
            () -> LoggingUtils.log(Level.SEVERE, "single-logger-output"));

        assertEquals("", stdout);
    }

    private static Logs drainedLogs() {
        Logs logs = mock(Logs.class);
        when(logs.getQueueLock()).thenReturn(new Object());
        when(logs.getCacheLock()).thenReturn(new Object());
        when(logs.getRawRequest()).thenReturn(new LinkedList<>());
        when(logs.getLogPayloadCache()).thenReturn(new ArrayList<>());
        return logs;
    }

    private static ExecutionContext context() {
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.getFunctionName()).thenReturn("LogForwarder");
        when(context.getInvocationId()).thenReturn("test-invocation");
        when(context.getLogger()).thenReturn(mock(Logger.class));
        return context;
    }

    private interface FlushAction {
        void run(ApiCallback callback);
    }

    private static class TestReliableLogs extends LogEventForwarder.ReliableBatchLogs {
        private final FlushAction action;
        private final int timeoutMs;

        TestReliableLogs(ApiCallback callback, FlushAction action) {
            this(callback, action, 1000);
        }

        TestReliableLogs(ApiCallback callback, FlushAction action, int timeoutMs) {
            super(new Configuration("company", "id", "key", "", null), 1, false, callback);
            this.action = action;
            this.timeoutMs = timeoutMs;
        }

        void flushOne() {
            setLogPayloadCache(new ArrayList<>(List.of(
                new LogsInput("message", Collections.emptyMap(), "1", Collections.emptyMap()))));
            doRequest();
        }

        @Override
        protected void performBatchRequest() {
            action.run(getApiCallback());
        }

        @Override
        protected int batchRequestTimeoutMs() {
            return timeoutMs;
        }
    }

    private static class RecordingCallback implements ApiCallback {
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        @Override
        public void onFailure(ApiException e, int statusCode, Map responseHeaders) {
            failures.incrementAndGet();
        }

        @Override
        public void onSuccess(Object responseBody, int statusCode, Map responseHeaders) {
            successes.incrementAndGet();
        }

        @Override
        public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {
        }

        @Override
        public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {
        }
    }
}
