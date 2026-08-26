package dev.twme.sculpt.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Produces concise default logs while retaining stack traces at FINE level. */
public final class ExceptionSummary {

    private static final int MAX_SUMMARY_LENGTH = 240;

    private ExceptionSummary() {
    }

    public static void log(final Logger logger, final Level level,
                           final String context, final Throwable failure) {
        logger.log(level, context + ": " + describe(failure));
        if (failure != null && logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, context, failure);
        }
    }

    public static String describe(final Throwable failure) {
        if (failure == null) return "unknown error";

        Throwable selected = failure;
        final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure;
                current != null && visited.add(current);
                current = current.getCause()) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                selected = current;
            }
        }

        String type = selected.getClass().getSimpleName();
        if (type.isBlank()) type = selected.getClass().getName();
        final String message = selected.getMessage();
        final String summary = message == null || message.isBlank()
            ? type
            : type + ": " + message.replaceAll("\\s+", " ").trim();
        if (summary.length() <= MAX_SUMMARY_LENGTH) return summary;
        return summary.substring(0, MAX_SUMMARY_LENGTH - 3) + "...";
    }
}
