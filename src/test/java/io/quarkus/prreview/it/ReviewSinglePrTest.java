package io.quarkus.prreview.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.quarkus.prreview.it.support.FakeGh;
import io.quarkus.prreview.it.support.JevWireMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

/** {@code review <pr>} end to end: the fake {@code gh}, {@code jev} in WireMock, the fallback summary. */
@QuarkusMainTest
@WithTestResource(FakeGh.class)
class ReviewSinglePrTest {

    static JevWireMock jev;

    @TempDir
    Path out;

    @BeforeAll
    static void startJev() {
        jev = JevWireMock.start();
    }

    @AfterAll
    static void stopJev() {
        jev.close();
    }

    @BeforeEach
    void resetJev() {
        jev.reset();
    }

    @Test
    void classifiesOnePrWithOneJevCall(QuarkusMainLauncher launcher) throws IOException {
        LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        Path report = out.resolve("review-101.md");
        List<String> stdout = result.getOutputStream();
        assertTrue(stdout.contains("PR #101 – test adequacy"), result.getOutput());
        assertTrue(stdout.contains("  behaviour 0.88  tests: no-test-changes 0.64"), result.getOutput());
        assertTrue(stdout.contains("Summary (fallback template): jev classification: behaviour 0.88; tests"
                + " no-test-changes 0.64."), result.getOutput());
        assertTrue(stdout.contains("Report: " + report), result.getOutput());

        List<LoggedRequest> posts = jevPosts();
        assertEquals(1, posts.size());
        String markdown = Files.readString(report);
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
    void testsOnlyPrIsAlsoOneJevCall(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("review", "102", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        assertEquals(1, jevPosts().size());
        assertTrue(jevPosts().getFirst().getBodyAsString().contains("test-adequacy.behaviour"));
    }

    @Test
    void duplicatePrIsProcessedOnce(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("review", "101", "101", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        assertEquals(1, result.getOutputStream().stream().filter(line -> line.startsWith("PR #101")).count(),
                result.getOutput());
        assertEquals(1, jevPosts().size());
    }

    private static List<LoggedRequest> jevPosts() {
        return jev.posts();
    }
}
