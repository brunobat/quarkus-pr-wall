package io.quarkus.prreview.classification.submission;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The project context that starts every {@code jev} input: what Quarkus is and its repository layout. It is bundled
 * with the app, the same for every PR, and names no answer.
 */
public final class ProjectContext {

    static final String RESOURCE = "project-context/quarkusio-quarkus.txt";

    public static final String TEXT = read();

    private ProjectContext() {
    }

    private static String read() {
        try (InputStream in = ProjectContext.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
