package io.quarkus.prreview.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * {@code util} holds only code that knows nothing about PRs, {@code jev} or GitHub: nothing in it imports another
 * package of the app or names an external system. Code that does belongs in its domain's package.
 */
class UtilIndependenceTest {

    static final Path UTIL = Path.of("src/main/java/io/quarkus/prreview/util");

    private static final Pattern APP_IMPORT = Pattern.compile("^import (static )?io\\.quarkus\\.prreview\\.(?!util\\.)",
            Pattern.MULTILINE);
    private static final Pattern EXTERNAL = Pattern.compile("(?i)\\b(gh|jev|typesafe|anthropic)\\b");

    @Test
    void utilDependsOnNothingElseInTheApp() throws IOException {
        List<Path> files = files();
        assertFalse(files.isEmpty());
        List<String> found = new ArrayList<>();
        for (Path file : files) {
            // Comments may say what the helpers are used for; only code is checked.
            String code = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
            if (APP_IMPORT.matcher(code).find()) {
                found.add(file.getFileName() + ": imports another app package");
            }
            if (EXTERNAL.matcher(code).find()) {
                found.add(file.getFileName() + ": names an external system");
            }
        }
        assertEquals(List.of(), found);
    }

    private static List<Path> files() throws IOException {
        try (Stream<Path> files = Files.walk(UTIL)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }
}
