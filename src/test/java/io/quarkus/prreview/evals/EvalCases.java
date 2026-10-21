package io.quarkus.prreview.evals;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the eval corpus from {@code src/test/resources/evals} (plan §13).
 *
 * <p>Cases are listed in an explicit {@code evals/cases.txt} manifest rather than discovered by scanning
 * the classpath: directory listing over a classpath is not portable (and not native-image-safe), and an
 * explicit manifest makes "a case silently stopped running" impossible to miss in review.
 */
final class EvalCases {

    private static final String ROOT = "evals/";
    private static final String MANIFEST = ROOT + "cases.txt";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalCases() {
    }

    /** Every case in the manifest, in manifest order. */
    static List<EvalCase> all() {
        List<EvalCase> cases = new ArrayList<>();
        for (String entry : manifest()) {
            cases.add(load(entry));
        }
        return List.copyOf(cases);
    }

    /** The recorded unified diff for a case, read from the sibling file its {@code diffFile} names. */
    static String diff(EvalCase c) {
        return read(ROOT + c.diffFile());
    }

    private static List<String> manifest() {
        List<String> entries = new ArrayList<>();
        try (BufferedReader r = reader(MANIFEST)) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.strip();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    entries.add(s);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException(MANIFEST + " lists no cases");
        }
        return entries;
    }

    private static EvalCase load(String entry) {
        try {
            return MAPPER.readValue(read(ROOT + entry), EvalCase.class);
        } catch (IOException e) {
            throw new IllegalStateException("could not parse eval case " + entry + ": " + e.getMessage(), e);
        }
    }

    private static BufferedReader reader(String resource) {
        InputStream in = EvalCases.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("missing eval resource: " + resource);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static String read(String resource) {
        try (BufferedReader r = reader(resource)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
