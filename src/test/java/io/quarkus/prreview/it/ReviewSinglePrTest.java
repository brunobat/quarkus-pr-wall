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

    static final String TEMPLATE = "jev classification: behaviour 0.88; tests-changed 0.08; reach no-test-changes 0.83;"
            + " assert no-test-changes 0.88.";
    static final String TIMEOUT_PROPERTY = "quarkus.langchain4j.anthropic.timeout";
    /** Profile-qualified, so it outranks the test profile's key in {@code application.yml}. */
    static final String API_KEY_PROPERTY = "%test.quarkus.langchain4j.anthropic.api-key";
    static final String CONTEXT_PROPERTY = "pr-review.jev.project-context";
    static final Path BUNDLED_CONTEXT = Path.of("src/main/resources/project-context/quarkusio-quarkus.txt");

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
    void gitHubFactsNeverReachJevOrClaude(QuarkusMainLauncher launcher) {
        // 112 is 101's twin (same metadata and diff) with no size, checks or comments: every fact is unavailable.
        LaunchResult rich = launcher.launch("review", "101", "--output-dir", out.toString());
        assertTrue(rich.getOutput().contains("CI: 5 of 9 not passed"), rich.getOutput());
        List<String> jevRich = jev.posts().stream().map(LoggedRequest::getBodyAsString).toList();
        List<String> claudeRich = claude.posts().stream().map(LoggedRequest::getBodyAsString).toList();
        jev.reset();
        claude.reset();

        LaunchResult bare = launcher.launch("review", "112", "--output-dir", out.toString());
        assertTrue(bare.getOutput().contains("Size: unavailable"), bare.getOutput());
        assertTrue(bare.getOutput().contains("CI: unavailable"), bare.getOutput());
        assertTrue(bare.getOutput().contains("Author: unavailable"), bare.getOutput());
        assertTrue(rich.getOutput().contains("Author: alice (team) · AI: none declared"), rich.getOutput());

        assertEquals(1, jevRich.size());
        assertEquals(1, claudeRich.size());
        assertEquals(jevRich, jev.posts().stream().map(LoggedRequest::getBodyAsString).toList());
        assertEquals(claudeRich, claude.posts().stream().map(LoggedRequest::getBodyAsString).toList());
    }

    @Test
    void classifiesOnePrWithOneJevCallAndOneClaudeCall(QuarkusMainLauncher launcher) throws IOException {
        LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        Path report = out.resolve("review-101.md");
        List<String> stdout = result.getOutputStream();
        assertTrue(stdout.contains("PR #101 - test adequacy"), result.getOutput());
        assertTrue(stdout.contains("  behaviour 0.88  tests-changed 0.08  reach: no-test-changes 0.83  assert: no-test-changes 0.88"),
                result.getOutput());
        assertTrue(stdout.contains("Summary (Claude): " + ClaudeWireMock.SUMMARY), result.getOutput());
        assertTrue(stdout.contains("Report: " + report), result.getOutput());

        List<LoggedRequest> posts = jevPosts();
        assertEquals(1, posts.size());
        assertEquals(1, claude.posts().size());
        String markdown = Files.readString(report);
        assertTrue(markdown.contains("## Classification\n"), markdown);
        assertTrue(markdown.contains("## Summary\n\n**Claude (claude-opus-5-5):** " + ClaudeWireMock.SUMMARY), markdown);
        assertTrue(markdown.matches("(?s).*\n- Claude submission: \\d+ characters \\(system and user messages\\)\n.*"),
                markdown);
        assertTrue(markdown.contains("\n- Claude tokens: 100 input, 20 output\n"), markdown);
        assertTrue(markdown.contains("## Changed files\n"), markdown);
        assertTrue(markdown.contains("docs/src/main/asciidoc/foo.adoc"), markdown);
        assertTrue(markdown.contains("## Run\n"), markdown);
        // The size the app measured before sending is the length of the body jev received.
        assertTrue(markdown.contains("- Submission size: " + posts.getFirst().getBodyAsString().length()
                + " characters (limit 60000)"), markdown);
        assertTrue(markdown.contains("- jev model: jev-test"), markdown);
        // jev's input starts with the bundled project context, which the footer names.
        String context = Files.readString(BUNDLED_CONTEXT).strip();
        assertTrue(posts.getFirst().getBodyAsString().contains(jsonString(context + "\n\nChanged files:\n")),
                posts.getFirst().getBodyAsString());
        assertTrue(markdown.contains("- Project context: classpath:project-context/quarkusio-quarkus.txt ("
                + context.length() + " characters)\n"), markdown);
        assertTrue(markdown.contains("- jev tokens: 1234 input, 56 output"), markdown);
        assertTrue(markdown.contains("| behaviour | Does this PR change the runtime behaviour of the software? | 0.88 |"),
                markdown);
        try (var files = Files.list(out)) {
            assertEquals(List.of(report), files.toList(), "no temporary file is left");
        }
    }

    @Test
    void whoWroteAPrIsLookedUpWhileJevIsAsked(QuarkusMainLauncher launcher) {
        // 118 is 101's twin whose author-association lookup takes 3s; jev also takes 3s. Done one after the other
        // they would add 6s to the run; overlapped they add about 3s. The first launch is a warm-up, so that start-up
        // costs are not counted in the baseline.
        launcher.launch("review", "101", "--output-dir", out.toString());
        long start = System.nanoTime();
        LaunchResult baseline = launcher.launch("review", "101", "--output-dir", out.toString());
        Duration fast = Duration.ofNanos(System.nanoTime() - start);
        assertEquals(0, baseline.exitCode(), baseline.getErrorOutput());

        jev.answerAfter(Duration.ofSeconds(3));
        start = System.nanoTime();
        LaunchResult slow = launcher.launch("review", "118", "--output-dir", out.toString());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(0, slow.exitCode(), slow.getErrorOutput());
        assertTrue(slow.getOutput().contains("Author: alice (team) · AI: none declared"), slow.getOutput());
        Duration added = elapsed.minus(fast);
        assertTrue(added.compareTo(Duration.ofMillis(2_500)) >= 0, "jev's delay was not waited for: " + added);
        assertTrue(added.compareTo(Duration.ofMillis(4_500)) < 0, "the lookups did not overlap jev: " + added);
    }

    @Test
    void reportsGoToReportsInTheCurrentFolderByDefault(QuarkusMainLauncher launcher) throws IOException {
        Path report = Path.of("reports", "review-101.md");
        Path report406 = Path.of("reports", "review-406.md");
        boolean dirExisted = Files.isDirectory(report.getParent());
        byte[] previous = Files.exists(report) ? Files.readAllBytes(report) : null;
        byte[] previous406 = Files.exists(report406) ? Files.readAllBytes(report406) : null;
        try {
            // A classified PR and one whose diff GitHub refuses, with no options at all.
            LaunchResult result = launcher.launch("review", "101", "406");

            assertEquals(0, result.exitCode(), result.getErrorOutput());
            assertTrue(result.getOutputStream().contains("Report: " + report), result.getOutput());
            assertTrue(Files.readString(report).startsWith("# PR #101: Fix the frobnicator"));
            assertTrue(result.getOutputStream().contains("Size: 2 files changed, +12 -3"), result.getOutput());
            assertTrue(Files.readString(report).contains("## Size (GitHub)\n\n- Changed files: 2\n"));
            assertTrue(result.getOutputStream().contains("CI: 5 of 9 not passed"), result.getOutput());
            assertTrue(Files.readString(report).contains("## CI checks (GitHub)\n\n5 of 9 not passed.\n"));
            assertTrue(result.getOutputStream().contains("Size: 1244 files changed, +17 -19751"), result.getOutput());
            assertTrue(result.getOutputStream().contains("CI: unavailable"), result.getOutput());
            assertTrue(result.getOutputStream().contains("Author: alice (team) · AI: none declared"),
                    result.getOutput());
            assertTrue(Files.readString(report).contains("## Provenance (GitHub)\n\nAuthor: alice (team)"));
            // GitHub refused 406's diff; who wrote it is still shown.
            assertTrue(result.getOutputStream().contains("Author: frank (team) · AI: none declared"),
                    result.getOutput());
            assertGitHubSections(report406);

            // The same PR over jev's submission limit still shows GitHub's facts.
            System.setProperty("pr-review.jev.max-submission-tokens", "1000");
            try {
                result = launcher.launch("review", "101");
            } finally {
                System.clearProperty("pr-review.jev.max-submission-tokens");
            }
            assertTrue(result.getOutput().contains("exceeds limit 1000"), result.getOutput());
            assertTrue(result.getOutputStream().contains("Size: 2 files changed, +12 -3"), result.getOutput());
            assertTrue(result.getOutputStream().contains("CI: 5 of 9 not passed"), result.getOutput());
            assertGitHubSections(report);
        } finally {
            // Leave the working folder as it was.
            restore(report, previous);
            restore(report406, previous406);
            if (!dirExisted) {
                Files.deleteIfExists(report.getParent());
            }
        }
    }

    private static void assertGitHubSections(Path report) throws IOException {
        String markdown = Files.readString(report);
        assertTrue(markdown.contains("\n## Size (GitHub)\n"), markdown);
        assertTrue(markdown.contains("\n## CI checks (GitHub)\n"), markdown);
        assertTrue(markdown.contains("\n## Provenance (GitHub)\n\nAuthor: "), markdown);
    }

    private static void restore(Path file, byte[] previous) throws IOException {
        if (previous != null) {
            Files.write(file, previous);
        } else {
            Files.deleteIfExists(file);
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
        // One attempt, no retry, then the template.
        assertEquals(1, claude.posts().size());
    }

    @Test
    void aSlowClaudeGivesTheFallbackTemplate(QuarkusMainLauncher launcher) {
        claude.delay(Duration.ofSeconds(30));
        System.setProperty(TIMEOUT_PROPERTY, "1s");
        try {
            long start = System.nanoTime();
            LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertEquals(0, result.exitCode(), result.getErrorOutput());
            assertTrue(result.getOutputStream().contains("Summary (fallback template): " + TEMPLATE), result.getOutput());
            assertEquals(1, claude.posts().size());
            // The run does not wait for Claude's answer; the exact bound is checked in SummaryServiceTimeoutTest.
            assertTrue(took.compareTo(Duration.ofSeconds(20)) < 0, "waited for the slow answer: " + took);
        } finally {
            System.clearProperty(TIMEOUT_PROPERTY);
        }
    }

    @Test
    void aTimeoutThatIsNotPositiveStopsTheRunBeforeAnyPr(QuarkusMainLauncher launcher) {
        System.setProperty(TIMEOUT_PROPERTY, "0s");
        try {
            LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

            assertEquals(1, result.exitCode(), result.getOutput());
            // Under @QuarkusMainTest the startup failure's log lines are captured with stdout.
            String output = result.getOutput() + result.getErrorOutput();
            assertTrue(output.contains("ANTHROPIC_TIMEOUT must be positive"), output);
            assertEquals(0, jevPosts().size());
            assertEquals(0, claude.posts().size());
        } finally {
            System.clearProperty(TIMEOUT_PROPERTY);
        }
    }

    @Test
    void withoutAClaudeKeyTheTimeoutIsNotUsed(QuarkusMainLauncher launcher) {
        System.setProperty(API_KEY_PROPERTY, "not-set");
        System.setProperty(TIMEOUT_PROPERTY, "0s");
        try {
            LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

            assertEquals(0, result.exitCode(), result.getOutput());
            assertTrue(result.getOutputStream().contains("Summary (fallback template): " + TEMPLATE), result.getOutput());
        } finally {
            System.clearProperty(API_KEY_PROPERTY);
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
            String markdown = Files.readString(out.resolve("review-101.md"));
            assertTrue(markdown.contains("- Summary: fallback template"), markdown);
            assertTrue(markdown.contains("- Claude submission: not sent\n"), markdown);
            assertEquals(1, jevPosts().size());
            assertEquals(0, claude.posts().size());
        } finally {
            System.clearProperty(API_KEY_PROPERTY);
        }
    }

    @Test
    void anotherProjectContextIsSentWithoutACodeChange(QuarkusMainLauncher launcher) throws IOException {
        Path context = Files.writeString(out.resolveSibling(out.getFileName() + "-context.txt"),
                "Project context: a stand-in repository.\n");
        System.setProperty(CONTEXT_PROPERTY, context.toString());
        try {
            LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

            assertEquals(0, result.exitCode(), result.getOutput());
            assertTrue(jevPosts().getFirst().getBodyAsString().contains(
                    jsonString("Project context: a stand-in repository.\n\nChanged files:\n")));
            assertTrue(Files.readString(out.resolve("review-101.md")).contains("- Project context: " + context
                    + " (39 characters)\n"));
        } finally {
            System.clearProperty(CONTEXT_PROPERTY);
            Files.deleteIfExists(context);
        }
    }

    @Test
    void anUnreadableProjectContextStopsTheRunBeforeAnyPr(QuarkusMainLauncher launcher) throws IOException {
        System.setProperty(CONTEXT_PROPERTY, out.resolve("missing.txt").toString());
        try {
            LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

            assertEquals(1, result.exitCode(), result.getOutput());
            // One line, no stack trace.
            List<String> errors = result.getErrorStream();
            assertEquals(1, errors.size(), result.getErrorOutput());
            assertTrue(errors.getFirst().startsWith("error: JEV_PROJECT_CONTEXT: cannot read " + out.resolve("missing.txt")),
                    result.getErrorOutput());
            assertEquals(0, jevPosts().size());
            try (var files = Files.list(out)) {
                assertEquals(List.of(), files.toList(), "nothing written");
            }
        } finally {
            System.clearProperty(CONTEXT_PROPERTY);
        }
    }

    @Test
    void helpWorksWithAnUnreadableProjectContext(QuarkusMainLauncher launcher) {
        System.setProperty(CONTEXT_PROPERTY, out.resolve("missing.txt").toString());
        try {
            LaunchResult result = launcher.launch("review", "--help");

            assertEquals(0, result.exitCode(), result.getOutput());
            assertTrue(result.getOutput().contains("--output-dir"), result.getOutput());
        } finally {
            System.clearProperty(CONTEXT_PROPERTY);
        }
    }

    @Test
    void anEmptyProjectContextVariableMeansTheBundledOne(QuarkusMainLauncher launcher) throws IOException {
        // An exported but empty JEV_PROJECT_CONTEXT; the system property stands in for the environment variable.
        System.setProperty("JEV_PROJECT_CONTEXT", "");
        try {
            LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

            assertEquals(0, result.exitCode(), result.getOutput());
            assertTrue(Files.readString(out.resolve("review-101.md")).contains(
                    "- Project context: classpath:project-context/quarkusio-quarkus.txt ("));
        } finally {
            System.clearProperty("JEV_PROJECT_CONTEXT");
        }
    }

    /** {@code text} as it appears inside a JSON string: quotes, backslashes and line breaks escaped. */
    private static String jsonString(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static List<LoggedRequest> jevPosts() {
        return jev.posts();
    }
}
