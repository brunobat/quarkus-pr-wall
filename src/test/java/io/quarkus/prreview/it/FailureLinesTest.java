package io.quarkus.prreview.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.prreview.it.support.ClaudeWireMock;
import io.quarkus.prreview.it.support.FakeGh;
import io.quarkus.prreview.it.support.JevWireMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

/**
 * Each failed {@code gh} read gives one warning line naming the PR and {@code gh}'s message, with no stack trace.
 * Under {@code @QuarkusMainTest} log lines are captured with stdout, so the assertions read the captured output.
 */
@QuarkusMainTest
@WithTestResource(FakeGh.class)
class FailureLinesTest {

    static JevWireMock jev;
    static ClaudeWireMock claude;

    @TempDir
    Path out;

    @BeforeAll
    static void startStubs() {
        jev = JevWireMock.start();
        claude = ClaudeWireMock.start();
    }

    @AfterAll
    static void stopStubs() {
        jev.close();
        claude.close();
    }

    @Test
    void eachFailedReadIsOneWarningLine(QuarkusMainLauncher launcher) {
        // 130: every bulk read and its size, checks and authorship reads fail; 131: its metadata read fails.
        LaunchResult result = launcher.launch("review", "130", "131", "--output-dir", out.toString());

        String output = result.getOutput();
        List<String> lines = output.lines().toList();
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("\tat ")), output);
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("Caused by: ")), output);

        String rateLimit = "gh: HTTP 403: API rate limit exceeded for installation ID 1234. (https://api.github.com/graphql)";
        for (String fact : List.of("sizes", "checks", "authorships")) {
            assertOneWarning(lines, "bulk " + fact + " for PRs [130, 131] failed; each is read on its own: " + rateLimit,
                    output);
        }
        assertOneWarning(lines, "PR #130: size unavailable: " + rateLimit, output);
        assertOneWarning(lines, "PR #130: checks unavailable: gh: HTTP 502: Bad Gateway (https://api.github.com/graphql)",
                output);
        assertOneWarning(lines, "PR #130: provenance unavailable: gh: error connecting to api.github.com", output);
        assertOneWarning(lines, "PR #131: not classified: gh: error connecting to api.github.com", output);

        // The facts still read unavailable, and the reason carries gh's message.
        String block130 = ReviewListTest.block(result.getOutputStream(), 130);
        assertTrue(block130.contains("Size: unavailable\n"), block130);
        assertTrue(block130.contains("CI: unavailable\n"), block130);
        assertTrue(block130.contains("Author: unavailable\n"), block130);
        String block131 = ReviewListTest.block(result.getOutputStream(), 131);
        assertTrue(block131.contains("  not classified: gh: error connecting to api.github.com\n"), block131);
    }

    private static void assertOneWarning(List<String> lines, String message, String output) {
        List<String> matching = lines.stream().filter(l -> l.endsWith(message)).toList();
        assertEquals(1, matching.size(), message + "\n" + output);
        assertTrue(matching.getFirst().contains(" WARN "), matching.getFirst());
    }
}
