package io.quarkus.prreview.util;

import org.jboss.logging.Logger;

import io.quarkus.prreview.report.PlainText;

/**
 * Expected failures (a {@code gh} or {@code jev} call that failed) logged as one warning line: what failed and the
 * failure's message on one line. The stack trace is added only when the logger is at debug level, so a normal run
 * shows one line per failure.
 */
public final class FailureLog {

    private FailureLog() {
    }

    /** Logs {@code <what>: <message>}, with {@code failure} attached only at debug level. */
    public static void warn(Logger log, String what, Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
        String line = what + ": " + PlainText.oneLine(message);
        if (log.isDebugEnabled()) {
            log.warn(line, failure);
        } else {
            log.warn(line);
        }
    }
}
