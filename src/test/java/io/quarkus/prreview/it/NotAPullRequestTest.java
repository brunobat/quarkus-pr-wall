package io.quarkus.prreview.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
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
 * A number GitHub says is not a pull request: one line, no report, no further lookups. The fake gh has no fixture for
 * 58521, so it fails it as the real gh does and answers 404 on its REST path.
 */
@QuarkusMainTest
@WithTestResource(FakeGh.class)
class NotAPullRequestTest {

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
    void aNumberThatIsNotAPullRequestIsOneLine(QuarkusMainLauncher launcher) throws IOException {
        int before = Files.exists(FakeGh.CALLS_LOG) ? Files.readAllLines(FakeGh.CALLS_LOG).size() : 0;

        LaunchResult result = launcher.launch("review", "101", "58521", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getOutput());
        List<String> stdout = result.getOutputStream();
        int line = stdout.indexOf("PR #58521: not a pull request in quarkusio/quarkus");
        assertTrue(line > 0, result.getOutput());
        assertEquals("", stdout.get(line - 1), result.getOutput());
        // The line is the number's whole block, and nothing else names it: no warning, no stack trace.
        assertEquals(List.of(stdout.get(line)), stdout.stream().filter(l -> l.contains("58521")).toList(),
                result.getOutput());
        assertTrue(stdout.stream().noneMatch(l -> l.startsWith("\tat ")), result.getOutput());
        assertFalse(Files.exists(out.resolve("review-58521.md")));
        assertTrue(Files.exists(out.resolve("review-101.md")));

        // Once GitHub answered 404, no other gh call names 58521.
        List<String> calls = Files.readAllLines(FakeGh.CALLS_LOG);
        calls = calls.subList(before, calls.size());
        int status = calls.indexOf("api repos/quarkusio/quarkus/pulls/58521 -i");
        assertTrue(status >= 0, calls.toString());
        assertTrue(calls.subList(status + 1, calls.size()).stream().noneMatch(c -> c.contains("58521")),
                calls.toString());
        // Before it, only the run-wide bulk reads and the metadata read named it.
        assertEquals(List.of("pr view 58521 --json number,title,url,state,isDraft -R quarkusio/quarkus"),
                calls.subList(0, status).stream().filter(c -> c.contains("58521") && !c.startsWith("pr list "))
                        .toList());
    }

    @Test
    void aSingleNumberThatIsNotAPullRequestIsAnError(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("review", "58521", "--output-dir", out.toString());

        assertEquals(1, result.exitCode(), result.getOutput());
        assertTrue(result.getOutputStream().contains("PR #58521: not a pull request in quarkusio/quarkus"),
                result.getOutput());
    }

    @Test
    void atDebugLevelGhsMessageAndTheStackTraceAreLogged(QuarkusMainLauncher launcher) {
        String category = "quarkus.log.category.\"io.quarkus.prreview\".level";
        System.setProperty(category, "DEBUG");
        LaunchResult result;
        try {
            result = launcher.launch("review", "58521", "--output-dir", out.toString());
        } finally {
            System.clearProperty(category);
            // The log manager is shared across launches in this JVM: put the level back for later tests.
            java.util.logging.Logger.getLogger("io.quarkus.prreview").setLevel(null);
        }

        String output = result.getOutput();
        assertTrue(output.contains("DEBUG [io.quarkus.prreview.triage.PrClassifierService] (main) PR #58521: not a pull request:"
                + " gh: GraphQL: Could not resolve to a PullRequest with the number of 58521."), output);
        assertTrue(output.lines().anyMatch(l -> l.startsWith("\tat ")), output);
    }
}
