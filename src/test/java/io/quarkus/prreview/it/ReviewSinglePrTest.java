package io.quarkus.prreview.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.quarkus.prreview.it.support.ClaudeWireMock;
import io.quarkus.prreview.it.support.FakeGh;
import io.quarkus.prreview.it.support.JevWireMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

/** {@code review <pr>} end to end: the fake {@code gh}, {@code jev} and Claude in WireMock. */
@QuarkusMainTest
@WithTestResource(FakeGh.class)
class ReviewSinglePrTest {

    static final String TEMPLATE = "jev classification: behaviour 0.88; tests no-test-changes 0.64.";
    static final String TIMEOUT_PROPERTY = "quarkus.langchain4j.anthropic.timeout";
    /** Profile-qualified, so it outranks the test profile's key in {@code application.yml}. */
    static final String API_KEY_PROPERTY = "%test.quarkus.langchain4j.anthropic.api-key";

    static JevWireMock jev;
    static ClaudeWireMock claude;

    @TempDir
    Path out;

    @BeforeAll
    static void startJev() {
        jev = JevWireMock.start();
        claude = ClaudeWireMock.start();
    }

    @AfterAll
    static void stopJev() {
        jev.close();
        claude.close();
    }

    @BeforeEach
    void resetJev() {
        jev.reset();
        claude.reset();
    }

    @Test
    void classifiesOnePrWithOneJevCallAndOneClaudeCall(QuarkusMainLauncher launcher) throws IOException {
        LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        Path report = out.resolve("review-101.md");
        List<String> stdout = result.getOutputStream();
        assertTrue(stdout.contains("PR #101 - test adequacy"), result.getOutput());
        assertTrue(stdout.contains("  behaviour 0.88  tests: no-test-changes 0.64"), result.getOutput());
        assertTrue(stdout.contains("Summary (Claude): " + ClaudeWireMock.SUMMARY), result.getOutput());
        assertTrue(stdout.contains("Report: " + report), result.getOutput());

        List<LoggedRequest> posts = jevPosts();
        assertEquals(1, posts.size());
        assertEquals(1, claude.posts().size());
        String markdown = Files.readString(report);
        assertTrue(markdown.contains("## Classification\n"), markdown);
        assertTrue(markdown.contains("## Summary\n\n**Claude (claude-opus-5-5):** " + ClaudeWireMock.SUMMARY), markdown);
        assertTrue(markdown.contains("## Changed files\n"), markdown);
        assertTrue(markdown.contains("docs/src/main/asciidoc/foo.adoc"), markdown);
        assertTrue(markdown.contains("## Run\n"), markdown);
        // The size the app measured before sending is the length of the body jev received.
        assertTrue(markdown.contains("- Submission size: " + posts.getFirst().getBodyAsString().length()
                + " characters (limit 60000)"), markdown);
        assertTrue(markdown.contains("- jev model: jev-test"), markdown);
        assertTrue(markdown.contains("- jev tokens: 1234 input, 56 output"), markdown);
        assertTrue(markdown.contains("| behaviour | Does this PR change the runtime behaviour of the software? | 0.88 |"),
                markdown);
        try (var files = Files.list(out)) {
            assertEquals(List.of(report), files.toList(), "no temporary file is left");
        }
    }

    @Test
    void reportsGoToReportsInTheCurrentFolderByDefault(QuarkusMainLauncher launcher) throws IOException {
        Path report = Path.of("reports", "review-101.md");
        boolean dirExisted = Files.isDirectory(report.getParent());
        byte[] previous = Files.exists(report) ? Files.readAllBytes(report) : null;
        try {
            LaunchResult result = launcher.launch("review", "101");

            assertEquals(0, result.exitCode(), result.getErrorOutput());
            assertTrue(result.getOutputStream().contains("Report: " + report), result.getOutput());
            assertTrue(Files.readString(report).startsWith("# PR #101: Fix the frobnicator"));
        } finally {
            // Leave the working folder as it was.
            if (previous != null) {
                Files.write(report, previous);
            } else {
                Files.deleteIfExists(report);
                if (!dirExisted) {
                    Files.deleteIfExists(report.getParent());
                }
            }
        }
    }

    @Test
    void testsOnlyPrIsAlsoOneJevCall(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("review", "102", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        assertEquals(1, jevPosts().size());
        assertTrue(jevPosts().getFirst().getBodyAsString().contains("test-adequacy.behaviour"));
        assertEquals(1, claude.posts().size());
    }

    @Test
    void aFailingClaudeGivesTheFallbackTemplate(QuarkusMainLauncher launcher) throws IOException {
        claude.failWith500();

        LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        assertTrue(result.getOutputStream().contains("Summary (fallback template): " + TEMPLATE), result.getOutput());
        String markdown = Files.readString(out.resolve("review-101.md"));
        assertTrue(markdown.contains("## Summary\n\n**fallback template:** " + TEMPLATE + "\n"), markdown);
        assertTrue(markdown.contains("- Summary: fallback template"), markdown);
        assertEquals(1, jevPosts().size());
        // The extension retries once (it cannot be set to zero retries), then the template is used.
        assertEquals(2, claude.posts().size());
    }

    @Test
    void aSlowClaudeGivesTheFallbackTemplateWithinTheTimeout(QuarkusMainLauncher launcher) {
        claude.delay(Duration.ofSeconds(10));
        System.setProperty(TIMEOUT_PROPERTY, "2s");
        try {
            long start = System.nanoTime();
            LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertEquals(0, result.exitCode(), result.getErrorOutput());
            assertTrue(result.getOutputStream().contains("Summary (fallback template): " + TEMPLATE), result.getOutput());
            assertTrue(claude.posts().size() >= 1);
            // The timeout bounds the whole call: a timed-out attempt and its retry would take 4 s.
            assertTrue(took.compareTo(Duration.ofMillis(3800)) < 0, "waited about one timeout: " + took);
        } finally {
            System.clearProperty(TIMEOUT_PROPERTY);
        }
    }

    @Test
    void withoutAClaudeKeyTheRunUsesTheTemplateAndNeverCallsClaude(QuarkusMainLauncher launcher) throws IOException {
        // "not-set" is what application.yml resolves to when ANTHROPIC_API_KEY is not set.
        System.setProperty(API_KEY_PROPERTY, "not-set");
        try {
            LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

            assertEquals(0, result.exitCode(), result.getErrorOutput());
            assertTrue(result.getOutputStream().contains("Summary (fallback template): " + TEMPLATE), result.getOutput());
            assertTrue(Files.readString(out.resolve("review-101.md")).contains("- Summary: fallback template"));
            assertEquals(1, jevPosts().size());
            assertEquals(0, claude.posts().size());
        } finally {
            System.clearProperty(API_KEY_PROPERTY);
        }
    }

    private static List<LoggedRequest> jevPosts() {
        return jev.posts();
    }
}
