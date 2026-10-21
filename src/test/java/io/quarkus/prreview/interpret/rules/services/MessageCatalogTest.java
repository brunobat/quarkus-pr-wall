package io.quarkus.prreview.interpret.rules.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the catalog's encoding. {@code Properties.load(InputStream)} decodes ISO-8859-1, so every em dash
 * in {@code messages.properties} reached the rendered report as mojibake — caught in a live run of
 * {@code review 57702}, not by a test, because the eval suite asserts on message <em>keys</em> rather than
 * on the text a contributor reads.
 */
class MessageCatalogTest {

    private final MessageCatalog catalog = new MessageCatalog();

    @Test
    void nonAsciiInAMessageSurvivesLoading() {
        String summary = catalog.summary("breaking-change-possible");

        assertTrue(summary.contains("—"), () -> "em dash was mangled on load: " + summary);
        assertFalse(summary.contains("Ã") || summary.contains("â"),
                () -> "ISO-8859-1 mojibake in the catalog: " + summary);
    }

    /** Every shipped message, not just the one that happened to break. */
    @Test
    void everyShippedMessageRoundTrips() {
        Properties expected = new Properties();
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("messages.properties");
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            expected.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertFalse(expected.isEmpty(), "messages.properties is missing or empty");
        expected.forEach((key, value) -> assertEquals(value, catalog.summary((String) key),
                () -> "catalog disagrees with messages.properties for " + key));
    }

    @Test
    void anUnknownKeyFallsBackToAHumanizedForm() {
        assertEquals("no such message", catalog.summary("no-such-message"));
    }
}
