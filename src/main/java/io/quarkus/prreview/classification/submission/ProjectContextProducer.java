package io.quarkus.prreview.classification.submission;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import io.quarkus.prreview.classification.model.ProjectContext;
import io.quarkus.prreview.config.ReviewConfig;
import io.quarkus.runtime.Startup;

/** Reads the project context once, at startup, so a bad {@code JEV_PROJECT_CONTEXT} stops the app before any PR. */
@ApplicationScoped
public class ProjectContextProducer {

    static final String CLASSPATH = "classpath:";

    @Produces
    @Singleton
    @Startup
    ProjectContext projectContext(ReviewConfig config) {
        return load(config.jev().projectContext());
    }

    /** Reads {@code source}: a file path, or {@code classpath:<resource>} for a bundled file. */
    static ProjectContext load(String source) {
        String text;
        try {
            text = source.startsWith(CLASSPATH) ? resource(source.substring(CLASSPATH.length()))
                    : Files.readString(Path.of(source));
        } catch (IOException e) {
            throw new IllegalArgumentException("JEV_PROJECT_CONTEXT: cannot read " + source + ": " + e, e);
        }
        if (text == null) {
            throw new IllegalArgumentException("JEV_PROJECT_CONTEXT: no bundled file " + source);
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("JEV_PROJECT_CONTEXT: " + source + " is blank");
        }
        return new ProjectContext(source, text.strip());
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
