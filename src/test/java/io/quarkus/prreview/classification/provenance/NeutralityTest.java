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
 * Who wrote a PR is reported, never judged. 116 and 117 are twins of 101 (same metadata and diff): 116 has a
 * first-time author and a {@code Co-Authored-By: Claude} trailer, 117 a team author and no trailer. What reaches
 * {@code jev} and Claude, the classification and the exit code must not differ; only the provenance line does.
 */
@QuarkusMainTest
@WithTestResource(FakeGh.class)
class NeutralityTest {

    static final String SIGNALS_PROPERTY = "pr-review.provenance.ai-signals";

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

    record Run(int exitCode, List<String> jevBodies, List<String> claudeBodies, String classification,
            String provenance) {
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
                report.substring(report.indexOf("## Classification")), provenance);
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
        assertEquals(declared.classification(), team.classification());

        assertEquals("Author: newcomer (first-time) · AI: declared (commit 1160000: \"co-authored-by: claude\")",
                declared.provenance());
        assertEquals("Author: teammate (team) · AI: none declared", team.provenance());
        assertNotEquals(declared.provenance(), team.provenance());
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
}
