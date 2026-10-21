package io.quarkus.prreview.interpret.rules.services;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Maps a rule's {@code message} key to a stable, deterministic one-line summary (design §10: evals
 * assert against this structured summary, not Opus prose). Opus later renders the human-facing fix
 * prose from the same finding, but the summary here never depends on a model, so the eval suite is
 * drift-proof.
 */
@ApplicationScoped
public class MessageCatalog {

    private final Properties messages = new Properties();

    public MessageCatalog() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("messages.properties")) {
            if (in != null) {
                // Explicitly UTF-8: load(InputStream) decodes ISO-8859-1, which turns the em dashes in
                // messages.properties into mojibake in the rendered report.
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    messages.load(reader);
                }
            }
        } catch (IOException e) {
            // Fall back to humanized keys.
        }
    }

    public String summary(String key) {
        if (key == null) {
            return "";
        }
        return messages.getProperty(key, humanize(key));
    }

    private static String humanize(String key) {
        return key.replace('-', ' ').replace('_', ' ');
    }
}
