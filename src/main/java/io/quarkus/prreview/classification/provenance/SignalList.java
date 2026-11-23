package io.quarkus.prreview.classification.provenance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.util.List;
import java.util.Locale;

import org.jboss.logging.Logger;

import io.quarkus.prreview.util.FileOrResource;

/**
 * A curated list kept outside the code: one entry per line, stripped and lower-cased, with blank lines and lines
 * starting with {@code #} skipped. A list that cannot be read logs one warning and is empty, so the command still runs
 * with no setup.
 *
 * @param source where the list was read from: a file path or {@code classpath:<resource>}
 * @param entries the entries, lower-cased, in file order
 */
public record SignalList(String source, List<String> entries) {

    private static final Logger LOG = Logger.getLogger(SignalList.class);

    public SignalList {
        entries = List.copyOf(entries);
    }

    /**
     * Reads {@code source}: a file path, or {@code classpath:<resource>} for a bundled file.
     *
     * @param setting the environment variable that names the list, for the warning
     */
    public static SignalList load(String source, String setting) {
        String text;
        try {
            text = FileOrResource.read(source);
        } catch (IOException | InvalidPathException | UncheckedIOException e) {
            LOG.warnf("%s: cannot read %s (%s); using an empty list", setting, source, e.getMessage());
            return new SignalList(source, List.of());
        }
        if (text == null) {
            LOG.warnf("%s: no bundled file %s; using an empty list", setting, source);
            return new SignalList(source, List.of());
        }
        return new SignalList(source, parse(text));
    }

    static List<String> parse(String text) {
        return text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .map(l -> l.toLowerCase(Locale.ROOT)).toList();
    }
}
