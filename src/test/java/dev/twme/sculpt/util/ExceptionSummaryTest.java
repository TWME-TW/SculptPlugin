package dev.twme.sculpt.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

class ExceptionSummaryTest {

    @Test
    void describesDeepestMeaningfulCauseOnOneLine() {
        final Throwable failure = new CompletionException(
            new IOException("connection reset\nwhile uploading"));

        assertEquals("IOException: connection reset while uploading",
            ExceptionSummary.describe(failure));
    }

    @Test
    void truncatesOversizedRemoteResponse() {
        final String summary = ExceptionSummary.describe(
            new IllegalStateException("x".repeat(400)));

        assertEquals(240, summary.length());
        assertTrue(summary.endsWith("..."));
    }

    @Test
    void warningHasNoThrowableAndStackTraceIsFineOnly() {
        final Logger logger = Logger.getLogger(
            ExceptionSummaryTest.class.getName() + ".capturing");
        final List<LogRecord> records = new ArrayList<>();
        final Handler handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        final IOException failure = new IOException("upload unavailable");
        try {
            ExceptionSummary.log(logger, Level.WARNING, "Skin upload failed", failure);
        } finally {
            logger.removeHandler(handler);
        }

        assertEquals(2, records.size());
        final LogRecord warning = records.get(0);
        assertEquals(Level.WARNING, warning.getLevel());
        assertEquals("Skin upload failed: IOException: upload unavailable",
            warning.getMessage());
        assertNull(warning.getThrown());

        final LogRecord diagnostic = records.get(1);
        assertEquals(Level.FINE, diagnostic.getLevel());
        assertSame(failure, diagnostic.getThrown());
        assertFalse(diagnostic.getMessage().contains("upload unavailable"));
    }
}
