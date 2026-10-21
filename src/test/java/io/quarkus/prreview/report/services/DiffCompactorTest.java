package io.quarkus.prreview.report.services;

import io.quarkus.prreview.report.model.CompactedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DiffCompactor} (plan §9.4, §13) on the deterministic path — no API key, so Opus
 * returns the caller's fallback and every assertion here is reproducible offline.
 *
 * <p>The properties that matter are the ones a verdict depends on: focal hunks survive byte-for-byte,
 * nothing is ever truncated mid-diff, and any loss is labelled and attributed to named files.
 */
class DiffCompactorTest {

    private static final String RUNTIME = "extensions/foo/runtime/src/main/java/io/quarkus/foo/Bar.java";
    private static final String DOCS = "docs/src/main/asciidoc/foo.adoc";
    private static final String OTHER = "extensions/foo/runtime/src/main/java/io/quarkus/foo/Baz.java";

    private static DiffCompactor compactorWithoutOpus() {
        OpusProseClient opus = new OpusProseClient();
        opus.apiKey = Optional.empty(); // not configured → generate() returns the deterministic fallback
        DiffCompactor c = new DiffCompactor();
        c.opus = opus;
        c.compactionMaxTokens = 2048;
        return c;
    }

    /** A file section with {@code added} added lines and one removed line. */
    private static String section(String path, int added) {
        StringBuilder sb = new StringBuilder("diff --git a/" + path + " b/" + path + "\n")
                .append("--- a/").append(path).append('\n')
                .append("+++ b/").append(path).append('\n')
                .append("@@ -1,1 +1,").append(added).append(" @@\n")
                .append("-gone\n");
        for (int i = 0; i < added; i++) {
            sb.append("+line ").append(i).append('\n');
        }
        return sb.toString();
    }

    @Test
    void leavesADiffThatFitsCompletelyAlone() {
        String diff = section(RUNTIME, 3);
        CompactedDiff out = compactorWithoutOpus().compact(diff, Set.of(RUNTIME), 10_000);

        assertEquals(diff, out.text());
        assertFalse(out.applied(), "a diff within budget is never touched");
        assertEquals("verbatim", out.method());
        assertEquals(List.of(), out.summarizedFiles());
    }

    @Test
    void treatsANullOrBlankDiffAsVerbatimEmpty() {
        CompactedDiff out = compactorWithoutOpus().compact(null, Set.of(RUNTIME), 10);
        assertEquals("", out.text());
        assertFalse(out.applied());
        assertEquals(0, out.originalChars());
    }

    @Test
    void stubsNonFocalFilesAndKeepsFocalHunksByteForByte() {
        String focal = section(RUNTIME, 5);
        String docs = section(DOCS, 400);
        CompactedDiff out = compactorWithoutOpus()
                .compact(focal + docs, Set.of(RUNTIME), focal.length() + 200);

        assertTrue(out.applied());
        assertEquals("focal-only", out.method());
        assertTrue(out.text().contains(focal), "focal hunks must survive unparaphrased");
        assertFalse(out.text().contains("+line 399"), "non-focal hunks are summarized away");
        assertTrue(out.text().contains("non-focal file"), "the stub says what happened");
        assertEquals(List.of(DOCS), out.summarizedFiles());
        assertEquals(focal.length() + docs.length(), out.originalChars());
    }

    @Test
    void namesTheStubbedFileAndItsLineCountsSoNothingVanishesSilently() {
        String focal = section(RUNTIME, 5);
        String docs = section(DOCS, 400);
        CompactedDiff out = compactorWithoutOpus()
                .compact(focal + docs, Set.of(RUNTIME), focal.length() + 200);

        assertTrue(out.text().contains("diff --git a/" + DOCS + " b/" + DOCS),
                "the stub keeps the file header so the reader still sees the path");
        assertTrue(out.text().contains("+400/-1 lines"), "the stub carries the line counts it replaced");
    }

    @Test
    void summarizesOverflowDeterministicallyWhenOpusIsUnavailable() {
        String first = section(RUNTIME, 20);
        String second = section(OTHER, 400);
        CompactedDiff out = compactorWithoutOpus()
                .compact(first + second, Set.of(RUNTIME, OTHER), first.length() + 400);

        assertTrue(out.applied());
        assertEquals("deterministic", out.method(), "no API key → the per-file stat fallback, not Opus");
        assertTrue(out.text().contains(first), "what fits is still verbatim");
        assertTrue(out.text().contains("--- SUMMARY OF 1 FURTHER CHANGED FILE(S) (LOSSY) ---"),
                "the loss is labelled in the state text itself, not only in the metadata");
        assertTrue(out.text().contains("- " + OTHER + " (+400/-1 lines; hunks not shown)"),
                "the fallback still names the file and the size of what it dropped");
        assertEquals(List.of(OTHER), out.summarizedFiles());
    }

    @Test
    void treatsEveryFileAsFocalWhenTheCallerSuppliesNoClassification() {
        String a = section(RUNTIME, 20);
        String b = section(DOCS, 400);
        CompactedDiff out = compactorWithoutOpus().compact(a + b, Set.of(), a.length() + 400);

        assertEquals("deterministic", out.method(),
                "with nothing classified, no section may be stubbed as non-focal — fail safe");
        assertTrue(out.text().contains(a));
        assertEquals(List.of(DOCS), out.summarizedFiles());
    }

    @Test
    void splitsOnFileHeadersAndKeepsAPreambleAsItsOwnSection() {
        List<DiffCompactor.Section> sections =
                DiffCompactor.split("commit abc\n\n" + section(RUNTIME, 2) + section(DOCS, 3));

        assertEquals(List.of("(preamble)", RUNTIME, DOCS), sections.stream()
                .map(DiffCompactor.Section::path).toList());
        assertEquals(2, sections.get(1).added());
        assertEquals(1, sections.get(1).removed());
    }

    @Test
    void countsOnlyRealChangeLinesNotTheFileMarkers() {
        DiffCompactor.Section s = DiffCompactor.split(section(RUNTIME, 7)).get(0);
        assertEquals(7, s.added(), "+++ is a header, not an added line");
        assertEquals(1, s.removed(), "--- is a header, not a removed line");
    }
}
