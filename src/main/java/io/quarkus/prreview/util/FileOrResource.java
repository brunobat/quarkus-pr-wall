package io.quarkus.prreview.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a configured text source: a file path, or {@code classpath:<resource>} for a bundled file. */
public final class FileOrResource {

    public static final String CLASSPATH = "classpath:";

    private FileOrResource() {
    }

    /**
     * The text of {@code source}, as UTF-8, or {@code null} when it names a bundled file that does not exist.
     *
     * @throws IOException if the file cannot be read
     * @throws java.nio.file.InvalidPathException if {@code source} is not a valid path
     */
    public static String read(String source) throws IOException {
        if (!source.startsWith(CLASSPATH)) {
            return Files.readString(Path.of(source));
        }
        // Class loader resource names have no leading slash.
        String name = source.substring(CLASSPATH.length());
        String resource = name.startsWith("/") ? name.substring(1) : name;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
