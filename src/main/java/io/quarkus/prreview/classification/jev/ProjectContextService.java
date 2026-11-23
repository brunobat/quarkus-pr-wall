package io.quarkus.prreview.classification.jev;

import java.io.IOException;
import java.nio.file.InvalidPathException;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.prreview.classification.jev.model.ProjectContext;
import io.quarkus.prreview.config.ReviewConfig;
import io.quarkus.prreview.util.FileOrResource;

/**
 * Reads the project context on first use and keeps it. The command asks for it before the first PR, so a bad
 * {@code JEV_PROJECT_CONTEXT} is a one-line error rather than a startup failure that would also break
 * {@code --help}.
 */
@ApplicationScoped
public class ProjectContextService {

    private final String source;
    private volatile ProjectContext context;

    public ProjectContextService(ReviewConfig config) {
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
            text = FileOrResource.read(source);
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
}
