package com.logicmonitor.logs.azure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.microsoft.azure.functions.ExecutionContext;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;

public class LoggingUtils {
    private static final String LOG_LEVEL = "LOG_LEVEL";
    private static final Level DEFAULT_LOG_LEVEL = Level.WARNING;
    private static final Logger LOGGER;

    static {
        setupGlobalLogger();
        LOGGER = Logger.getLogger("LogForwarder");
        try {
            String logLevel = System.getenv(LOG_LEVEL);
            if (StringUtils.isNotBlank(logLevel)) {
                Level level = Level.parse(logLevel);
                LOGGER.setLevel(level);
            }
        } catch (IllegalArgumentException e) {
            LOGGER.setLevel(DEFAULT_LOG_LEVEL);
        }
    }

    private static void setupGlobalLogger() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s%n");
    }

    protected static void log(Level level, String message) {
        LOGGER.log(level, message);
    }

    /**
     * Logs a message with function name and invocation ID.
     *
     * @param context execution context
     * @param level logging level
     * @param msgSupplier produces the message to log
     */
    protected static void log(final ExecutionContext context, Level level,
        Supplier<String> msgSupplier) {
        LOGGER.log(level, () -> String.format("[%s][%s] %s",
            context.getFunctionName(), context.getInvocationId(), msgSupplier.get()));
    }
}
