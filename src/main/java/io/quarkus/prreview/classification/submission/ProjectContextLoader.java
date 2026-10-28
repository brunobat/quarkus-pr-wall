package io.quarkus.prreview.classification.submission;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.prreview.classification.model.ProjectContext;
import io.quarkus.prreview.config.ReviewConfig;

/**
 * Reads the project context on first use and keeps it. The command asks for it before the first PR, so a bad
 * {@code JEV_PROJECT_CONTEXT} is a one-line error rather than a startup failure that would also break
 * {@code --help}.
 */
@ApplicationScoped
public class ProjectContextLoader {

    static final String CLASSPATH = "classpath:";

    private final String source;
    private volatile ProjectContext context;

    public ProjectContextLoader(ReviewConfig config) {
        this.source = config.jev().projectContext();
    }

    /** The project context, read on the first call; a bad source throws {@link IllegalArgumentException}. */
    public ProjectContext get() {
        ProjectContext loaded = context;
        if (loaded == null) {
            loaded = load(source);
            context = loaded;
        }
        return loaded;
    }

    /** Reads {@code source}: a file path, or {@code classpath:<resource>} for a bundled file. */
    static ProjectContext load(String source) {
        String text;
        try {
            text = source.startsWith(CLASSPATH) ? resource(source.substring(CLASSPATH.length()))
                    : Files.readString(Path.of(source));
        } catch (IOException | InvalidPathException e) {
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
        // Class loader resource names have no leading slash.
        String resource = name.startsWith("/") ? name.substring(1) : name;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
