package com.logicmonitor.logs.azure;

import com.microsoft.azure.functions.ExecutionContext;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;

public class LoggingUtils {
    private static final String LOG_LEVEL = "LOG_LEVEL";
    private static final Level DEFAULT_LOG_LEVEL = Level.INFO;
    private static final Logger LOGGER;
    private static final Level CONFIGURED_LEVEL;

    static {
        setupGlobalLogger();
        LOGGER = Logger.getLogger("LogForwarder");
        CONFIGURED_LEVEL = resolveConfiguredLevel();
        LOGGER.setLevel(CONFIGURED_LEVEL);
    }

    private static Level resolveConfiguredLevel() {
        try {
            String logLevel = System.getenv(LOG_LEVEL);
            if (StringUtils.isNotBlank(logLevel)) {
                return Level.parse(logLevel.trim());
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to default
        }
        return DEFAULT_LOG_LEVEL;
    }

    private static void setupGlobalLogger() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s%n");
    }

    private static boolean isLoggable(Level level) {
        return level.intValue() >= CONFIGURED_LEVEL.intValue();
    }

    /**
     * Ensures the Azure Functions host logger accepts our configured level.
     */
    private static void applyConfiguredLevel(Logger azureLogger) {
        if (azureLogger == null) {
            return;
        }
        Level current = azureLogger.getLevel();
        if (current == null || current.intValue() > CONFIGURED_LEVEL.intValue()) {
            azureLogger.setLevel(CONFIGURED_LEVEL);
        }
    }

    private static void writeStdout(Level level, String message) {
        try {
            System.out.println(level.getName() + ": " + message);
            System.out.flush();
        } catch (Exception ignored) {
            // never fail the Function because of logging
        }
    }

    protected static void log(Level level, String message) {
        if (!isLoggable(level)) {
            return;
        }
        LOGGER.log(level, message);
        writeStdout(level, message);
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
        if (!isLoggable(level)) {
            return;
        }
        String message = String.format("[%s][%s] %s",
            context.getFunctionName(), context.getInvocationId(), msgSupplier.get());
        Logger azureLogger = context != null ? context.getLogger() : null;
        if (azureLogger != null) {
            applyConfiguredLevel(azureLogger);
            azureLogger.log(level, message);
        } else {
            LOGGER.log(level, message);
        }
        writeStdout(level, message);
    }
}
