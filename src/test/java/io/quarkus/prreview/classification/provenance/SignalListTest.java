package io.quarkus.prreview.classification.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignalListTest {

    @Test
    void readsAFileSkippingCommentsAndBlankLinesLowerCased(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("signals.txt");
        Files.writeString(file, """
                # a comment
                Co-Authored-By: Claude

                   generated with CLAUDE code  \t
                  # an indented comment
                🤖 Generated with
                """);

        SignalList list = SignalList.load(file.toString(), "PROVENANCE_AI_SIGNALS");

        assertEquals(List.of("co-authored-by: claude", "generated with claude code", "🤖 generated with"),
                list.entries());
        assertEquals(file.toString(), list.source());
    }

    @Test
    void readsABundledList() {
        assertEquals(List.of("dependabot", "quarkus-bot"),
                SignalList.load("classpath:provenance/automation-accounts.txt", "X").entries());
        assertEquals(List.of("dependabot", "quarkus-bot"),
                SignalList.load("classpath:/provenance/automation-accounts.txt", "X").entries());
    }

    @Test
    void aMissingListLogsOneWarningNamingItsSetting(@TempDir Path dir) {
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(SignalList.class.getName());
        logger.addHandler(handler);
        try {
            SignalList.load(dir.resolve("missing.txt").toString(), "PROVENANCE_AI_SIGNALS");
        } finally {
            logger.removeHandler(handler);
        }

        assertEquals(1, records.size(), records.toString());
        assertEquals(Level.WARNING, records.getFirst().getLevel());
        String message = new SimpleFormatter().formatMessage(records.getFirst());
        assertTrue(message.startsWith("PROVENANCE_AI_SIGNALS: cannot read "), message);
    }

    @Test
    void aMissingListIsEmptyNotAFailure(@TempDir Path dir) {
        assertEquals(List.of(), SignalList.load(dir.resolve("missing.txt").toString(), "X").entries());
        assertEquals(List.of(), SignalList.load("classpath:provenance/missing.txt", "X").entries());
        assertEquals(List.of(), SignalList.load(dir.toString(), "X").entries());
        assertEquals(List.of(), SignalList.load("bad\0path", "X").entries());
    }
}
