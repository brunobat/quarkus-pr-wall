package io.quarkus.prreview.classification.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import io.quarkus.prreview.it.support.ClaudeWireMock;
import io.quarkus.prreview.it.support.FakeGh;
import io.quarkus.prreview.it.support.JevWireMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

/**
 * Who wrote a PR is reported, never judged. 116, 117 and 120 are twins of 101 (same metadata and diff): 116 has a
 * first-time author and a {@code Co-Authored-By: Claude} trailer, 117 a team author and no trailer, 120 the same
 * first-time author as 116 and no trailer. What reaches
 * {@code jev} and Claude, the classification and the exit code must not differ; only the provenance line does.
 */
@QuarkusMainTest
@WithTestResource(FakeGh.class)
class NeutralityTest {

    static final String SIGNALS_PROPERTY = "pr-review.provenance.ai-signals";
    static final String AUTOMATION_PROPERTY = "pr-review.provenance.automation-accounts";

    static JevWireMock jev;
    static ClaudeWireMock claude;

    @TempDir
    Path out;

    @BeforeAll
    static void start() {
        jev = JevWireMock.start();
        claude = ClaudeWireMock.start();
    }

    @AfterAll
    static void stop() {
        jev.close();
        claude.close();
    }

    @BeforeEach
    void reset() {
        jev.reset();
        claude.reset();
    }

    record Run(int exitCode, List<String> jevBodies, List<String> claudeBodies, String report, String provenance) {
    }

    Run run(QuarkusMainLauncher launcher, int pr) throws IOException {
        jev.reset();
        claude.reset();
        Path dir = out.resolve(String.valueOf(pr));
        LaunchResult result = launcher.launch("review", String.valueOf(pr), "--output-dir", dir.toString());
        String report = Files.readString(dir.resolve("review-" + pr + ".md"));
        String provenance = result.getOutputStream().stream().filter(l -> l.startsWith("Author: ")).findFirst()
                .orElseThrow();
        return new Run(result.exitCode(), jev.posts().stream().map(LoggedRequest::getBodyAsString).toList(),
                claude.posts().stream().map(LoggedRequest::getBodyAsString).toList(),
                withoutProvenance(report).replaceFirst("^# PR #" + pr + ":", "# PR #N:"), provenance);
    }

    /**
     * The report with its provenance section cut out: everything that is not about who wrote the PR. The caller
     * replaces the PR's own number in the title, the only other line that differs between twins.
     */
    static String withoutProvenance(String report) {
        int start = report.indexOf("## Provenance (GitHub)");
        int end = report.indexOf("\n## ", start + 1);
        assertTrue(start >= 0 && end > start, report);
        return report.substring(0, start) + report.substring(end + 1);
    }

    @Test
    void whoWroteAPrAndWhetherItDeclaredAiChangeNothingButTheAuthorLine(QuarkusMainLauncher launcher)
            throws IOException {
        Run declared = run(launcher, 116);
        Run team = run(launcher, 117);

        assertEquals(0, declared.exitCode());
        assertEquals(1, declared.jevBodies().size());
        assertEquals(1, declared.claudeBodies().size());
        assertEquals(declared.exitCode(), team.exitCode());
        assertEquals(declared.jevBodies(), team.jevBodies());
        assertEquals(declared.claudeBodies(), team.claudeBodies());
        assertEquals(declared.report(), team.report());

        assertEquals("Author: newcomer (first-time) · AI: declared (commit 1160000: \"co-authored-by: claude\")",
                declared.provenance());
        assertEquals("Author: teammate (team) · AI: none declared", team.provenance());
        assertNotEquals(declared.provenance(), team.provenance());
    }

    @Test
    void declaringAiAloneChangesNothingButTheAuthorLine(QuarkusMainLauncher launcher) throws IOException {
        // 120 is 116 without the trailer: the same first-time author, so only the AI declaration differs.
        Run declared = run(launcher, 116);
        Run undeclared = run(launcher, 120);

        assertEquals(0, declared.exitCode());
        assertEquals(declared.exitCode(), undeclared.exitCode());
        assertEquals(declared.jevBodies(), undeclared.jevBodies());
        assertEquals(declared.claudeBodies(), undeclared.claudeBodies());
        assertEquals(declared.report(), undeclared.report());
        assertEquals("Author: newcomer (first-time) · AI: none declared", undeclared.provenance());
        assertNotEquals(declared.provenance(), undeclared.provenance());
    }

    @Test
    void aMissingSignalListDeclaresNothingAndTheRunSucceeds(QuarkusMainLauncher launcher) throws IOException {
        System.setProperty(SIGNALS_PROPERTY, out.resolve("no-such-list.txt").toString());
        LaunchResult result;
        try {
            result = launcher.launch("review", "116", "--output-dir", out.toString());
        } finally {
            System.clearProperty(SIGNALS_PROPERTY);
        }

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        assertTrue(result.getOutput().contains("Author: newcomer (first-time) · AI: none declared"),
                result.getOutput());
    }

    @Test
    void aMissingAutomationListMakesEveryBotAnAgentAndTheRunSucceeds(QuarkusMainLauncher launcher) {
        // 119 is a dependabot PR: dependabot's commits come back as dependabot[bot], the same account.
        LaunchResult routine = launcher.launch("review", "119", "--output-dir", out.toString());
        assertEquals(0, routine.exitCode(), routine.getErrorOutput());
        assertTrue(routine.getOutput().contains("Author: app/dependabot (bot) · AI: none declared\n"),
                routine.getOutput());

        System.setProperty(AUTOMATION_PROPERTY, out.resolve("no-such-list.txt").toString());
        LaunchResult result;
        try {
            result = launcher.launch("review", "119", "--output-dir", out.toString());
        } finally {
            System.clearProperty(AUTOMATION_PROPERTY);
        }

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        assertTrue(result.getOutput().contains("Author: app/dependabot (bot) · AI: agent (app/dependabot)\n"),
                result.getOutput());
    }
}
