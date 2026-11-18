package io.quarkus.prreview.classification.github.ci.botcomment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.github.ci.botcomment.BotStatusComment.Entry;
import io.quarkus.prreview.classification.github.ci.botcomment.BotStatusComment.FlakyTest;
import io.quarkus.prreview.clients.gh.model.GhComment;

/** Trimmed copies of real quarkus-bot comments, in {@code src/test/resources/bot-comments}. */
public class BotCommentParserTest {

    public static final String COMMIT_57715 = "4d456bf0dad6b5d357ea2d0a53bdb4f4ef00aec6";

    public static String body(String file) {
        try {
            return Files.readString(Path.of("src/test/resources/bot-comments", file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static GhComment bot(String file, String createdAt) {
        return new GhComment("quarkus-bot", body(file), createdAt);
    }

    @Test
    void readsFailingTestsNonTestFailuresFlakyTestsAndScanLinks() {
        BotStatusComment comment = BotCommentParser.parse(bot("2026-failures.md", "2026-10-22T13:46:09Z"))
                .orElseThrow();

        assertEquals("Quarkus CI", comment.workflow());
        assertEquals(COMMIT_57715, comment.commit());
        assertEquals(List.of("JVM Tests - JDK 21", "JVM Integration Tests - JDK 17"),
                List.copyOf(comment.failures().keySet()));
        assertEquals(List.of(
                new Entry("io.quarkus.websockets.next.test.maxframesize.MaxFrameSizeTest.testMaxFrameSize",
                        "java.util.concurrent.CompletionException: io.netty.channel.StacklessClosedChannelException",
                        "https://develocity.quarkus.io/scans/tests?tests.container=io.quarkus.websockets.next.test"
                                + ".maxframesize.MaxFrameSizeTest&tests.test=testMaxFrameSize"),
                new Entry("io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test",
                        "Timeout waiting for response - java.lang.RuntimeException",
                        "https://develocity.quarkus.io/scans/tests?tests.container=io.quarkus.vertx.http.filters"
                                + ".GracefulShutdownFilterTest&tests.test=test")),
                comment.failures().get("JVM Tests - JDK 21"));
        Entry build = comment.failures().get("JVM Integration Tests - JDK 17").getFirst();
        assertEquals(null, build.test());
        assertTrue(build.text().startsWith("Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin"
                + ":3.5.4:test (default-test) on project quarkus-integration-test-kubernetes-client-devservices: See "),
                build.text());
        assertTrue(build.text().endsWith("There was an error in the forked process"), build.text());
        assertEquals(Set.of(
                new FlakyTest("JVM Tests - JDK 21", "io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test"),
                new FlakyTest("JVM Tests - JDK 17 Windows",
                        "io.quarkus.undertow.test.compress.CompressionEnabledTestCase.testCompressed")),
                comment.flaky());
        // No build scan was produced for JVM Integration Tests - JDK 17 (":construction:").
        assertEquals(Map.of("JVM Tests - JDK 21", "https://develocity.quarkus.io/s/hzvs2pygcxpvk",
                "JVM Integration Tests - JDK 17 Windows", "https://develocity.quarkus.io/s/gfir72xr56l32"),
                comment.scanLinks());
    }

    @Test
    void aPassedRunListsOnlyFlakyTests() {
        BotStatusComment comment = BotCommentParser.parse(bot("2026-passed-with-flaky.md", "2026-09-24T23:59:28Z"))
                .orElseThrow();

        assertEquals("a718653ee237e844bbf0d8d0b4c08915a17a9bd4", comment.commit());
        assertEquals(Map.of(), comment.failures());
        assertEquals(Set.of("JVM Tests - JDK 21 Windows", "JVM Integration Tests - JDK 25 Semeru"),
                Set.copyOf(comment.flaky().stream().map(FlakyTest::job).toList()));
    }

    @Test
    void the2024LayoutWithItsOtherGlyphIsRead() {
        BotStatusComment comment = BotCommentParser.parse(bot("2024-cross-glyph.md", "2025-01-06T14:04:07Z"))
                .orElseThrow();

        List<Entry> entries = comment.failures().get("Quickstarts Compilation - JDK 17");
        assertEquals(2, entries.size());
        assertEquals("Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile "
                + "(default-compile) on project security-webauthn-quickstart: Compilation failure",
                entries.getFirst().text());
        assertEquals(Set.of(new FlakyTest("JVM Tests - JDK 21",
                "io.quarkus.smallrye.reactivemessaging.hotreload.ConnectorChangeTest.testUpdatingConnector")),
                comment.flaky());
    }

    @Test
    void the2022LayoutAndOtherAuthorsAreNotStatusComments() {
        assertTrue(BotCommentParser.parse(bot("2022-format.md", "2022-07-01T02:53:19Z")).isEmpty());
        assertTrue(BotCommentParser.parse(new GhComment("someone", body("2026-failures.md"), "2026-10-22T13:46:09Z"))
                .isEmpty());
        assertTrue(BotCommentParser.parse(new GhComment("quarkus-bot", "Thanks for your pull request!", "x"))
                .isEmpty());
    }

    @Test
    void theHeadCommitsCommentCreatedLastIsTheOneUsed() {
        String rerun = body("2026-failures.md").replace("JVM Tests - JDK 21", "JVM Tests - JDK 21 again");
        List<GhComment> comments = List.of(
                bot("2026-failures.md", "2026-10-22T13:46:09Z"),
                new GhComment("quarkus-bot", rerun, "2026-10-22T15:00:00Z"),
                new GhComment("someone", body("2026-failures.md"), "2026-10-22T16:00:00Z"),
                bot("2026-passed-with-flaky.md", "2026-10-23T00:00:00Z"));

        BotStatusComment chosen = BotCommentParser.forHead(comments, "Quarkus CI", COMMIT_57715).orElseThrow();
        assertTrue(chosen.failures().containsKey("JVM Tests - JDK 21 again"), chosen.toString());
        assertTrue(BotCommentParser.forHead(comments, "Quarkus Documentation CI", COMMIT_57715).isEmpty());
        assertTrue(BotCommentParser.forHead(comments, "Quarkus CI", "0".repeat(40)).isEmpty());
        assertTrue(BotCommentParser.hasBotComment(comments));
        assertFalse(BotCommentParser.hasBotComment(List.of(new GhComment("someone", "hi", "x"))));
    }

    @Test
    void aCommentWithoutACreationTimeCountsAsTheOldest() {
        String rerun = body("2026-failures.md").replace("JVM Tests - JDK 21", "JVM Tests - JDK 21 again");
        GhComment undated = new GhComment("quarkus-bot", rerun, null);

        BotStatusComment dated = BotCommentParser
                .forHead(List.of(undated, bot("2026-failures.md", "2026-10-22T13:46:09Z")), "Quarkus CI", COMMIT_57715)
                .orElseThrow();
        assertFalse(dated.failures().containsKey("JVM Tests - JDK 21 again"), dated.toString());
        BotStatusComment alone = BotCommentParser.forHead(List.of(undated), "Quarkus CI", COMMIT_57715).orElseThrow();
        assertTrue(alone.failures().containsKey("JVM Tests - JDK 21 again"), alone.toString());
    }
}
