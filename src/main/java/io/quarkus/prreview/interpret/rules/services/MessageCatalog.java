package io.quarkus.prreview.interpret.rules.services;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
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
                messages.load(in);
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
