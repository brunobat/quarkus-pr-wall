package io.quarkus.prreview.clients.gh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.clients.gh.model.GhCheck;
import io.quarkus.prreview.clients.gh.model.GhChecks;
import io.quarkus.prreview.clients.gh.model.GhComment;
import io.quarkus.prreview.clients.gh.model.GhSize;
import io.quarkus.prreview.config.ReviewConfig;
import io.quarkus.prreview.it.support.FakeGh;
import io.quarkus.prreview.pr.model.PullRequest;
import tools.jackson.databind.json.JsonMapper;

/** Runs the real {@link GhClient} against the fake {@code gh} script in {@code src/test/resources/fake-gh}. */
class GhClientTest {

    static final Path FAKE_GH_DIR = Path.of("src/test/resources/fake-gh").toAbsolutePath();
    static final String FAKE_GH = FAKE_GH_DIR.resolve("gh").toString();

    private final GhClient client = new GhClient(config(FAKE_GH, Duration.ofSeconds(1)), JsonMapper.builder().build());

    @Test
    void fetchesMetadataAndDiff() {
        PullRequest pr = client.fetch(101);

        assertEquals(101, pr.number());
        assertEquals("Fix the frobnicator", pr.title());
        assertEquals("https://github.com/quarkusio/quarkus/pull/101", pr.url());
        assertEquals("OPEN", pr.state());
        assertTrue(pr.diff().startsWith("diff --git a/core/runtime/src/main/java/io/quarkus/Foo.java"), pr.diff());
    }

    @Test
    void unknownPrCarriesGhMessage() {
        GhException e = assertThrows(GhException.class, () -> client.fetch(404));
        assertTrue(e.getMessage().contains("no pull requests found"), e.getMessage());
        assertNull(e.pullRequest());
    }

    @Test
    void tooLargeDiffMessageIsPropagated() {
        GhException e = assertThrows(GhException.class, () -> client.fetch(406));
        assertTrue(e.getMessage().contains("HTTP 406"), e.getMessage());
        assertTrue(e.getMessage().contains("too_large"), e.getMessage());
        // gh pr view succeeded; only the diff failed, so the title is kept.
        assertEquals("Reformat everything", e.pullRequest().title());
        assertEquals("https://github.com/quarkusio/quarkus/pull/406", e.pullRequest().url());
    }

    @Test
    void fetchesSizeCounts() {
        assertEquals(new GhSize(12, 3, 2), client.size(101));
    }

    @Test
    void sizeCountsAreKnownWhenTheDiffIsRefused() {
        assertEquals(new GhSize(17, 19751, 1244), client.size(406));
    }

    @Test
    void aMissingCountIsAFailureNotZero() {
        GhException e = assertThrows(GhException.class, () -> client.size(102));
        assertTrue(e.getMessage().contains("did not report additions"), e.getMessage());
    }

    @Test
    void sizeOfAnUnknownPrIsAGhFailure() {
        GhException e = assertThrows(GhException.class, () -> client.size(404));
        assertTrue(e.getMessage().contains("no pull requests found"), e.getMessage());
    }

    @Test
    void fetchesTheHeadCommitAndEveryKindOfCheck() {
        GhChecks checks = client.checks(101);

        assertEquals("ba9690af0e8bdc5d1c0f7a6c2b6b1e7d3c9a4f21", checks.headRefOid());
        assertEquals(9, checks.rollup().size());
        GhCheck failed = checks.rollup().get(1);
        assertEquals(new GhCheck(GhCheck.CHECK_RUN, "JVM Integration Tests - JDK 17", "Quarkus CI", "COMPLETED",
                "FAILURE", "https://github.com/quarkusio/quarkus/actions/runs/39914638446/job/120285311801", null, null,
                null), failed);
        GhCheck running = checks.rollup().get(4);
        assertEquals("IN_PROGRESS", running.status());
        assertEquals("", running.conclusion());
        assertEquals(new GhCheck(GhCheck.STATUS_CONTEXT, null, null, null, null, null, "CI Merge Gate", "FAILURE", ""),
                checks.rollup().getLast());
    }

    @Test
    void noChecksIsAnEmptyListNotAFailure() {
        assertEquals(List.of(), client.checks(103).rollup());
    }

    @Test
    void aMissingRollupIsAFailure() {
        GhException e = assertThrows(GhException.class, () -> client.checks(105));
        assertTrue(e.getMessage().contains("did not report statusCheckRollup"), e.getMessage());
    }

    @Test
    void fetchesCommentsOldestFirstWithTheirAuthors() {
        List<GhComment> comments = client.comments(105);

        assertEquals(List.of("quarkus-bot", "a-human"), comments.stream().map(GhComment::authorLogin).toList());
        assertTrue(comments.getFirst().body().startsWith("# Status for workflow `Quarkus CI`"),
                comments.getFirst().body());
        assertEquals("2026-10-23T12:00:00Z", comments.getFirst().createdAt());
    }

    @Test
    void commentsThatCannotBeReadAreAGhFailure() {
        GhException e = assertThrows(GhException.class, () -> client.comments(404));
        assertTrue(e.getMessage().contains("no pull requests found"), e.getMessage());
    }

    @Test
    void slowGhTimesOut() throws IOException, InterruptedException {
        GhException e = assertThrows(GhException.class, () -> client.fetch(99));
        assertTrue(e.getMessage().contains("timed out after"), e.getMessage());

        // gh's own children are killed too: a child holding the output pipes would otherwise outlive the timeout.
        List<String> log = Files.readAllLines(FakeGh.CALLS_LOG);
        long sleepPid = Long.parseLong(log.reversed().stream().filter(l -> l.startsWith("sleep-pid "))
                .findFirst().orElseThrow().substring("sleep-pid ".length()));
        assertTrue(endsWithin(sleepPid, 2000), "gh's child process " + sleepPid + " is still running");
    }

    private static boolean endsWithin(long pid, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(50);
        }
        return true;
    }

    @Test
    void missingBinaryIsAGhFailure() {
        GhClient missing = new GhClient(config("/nonexistent/gh", Duration.ofSeconds(1)), JsonMapper.builder().build());
        GhException e = assertThrows(GhException.class, () -> missing.fetch(101));
        assertTrue(e.getMessage().contains("cannot run /nonexistent/gh"), e.getMessage());
    }

    @Test
    void onlyReadOnlySubcommandsAreAllowed() {
        assertEquals(List.of(FAKE_GH, "pr", "view", "1", "-R", "quarkusio/quarkus"),
                client.command(List.of("pr", "view", "1")));
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("pr", "merge", "1")));
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("pr", "comment", "1")));
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("api", "repos/x/y/pulls/1")));
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("pr")));
    }

    static ReviewConfig config(String binary, Duration timeout) {
        return new ReviewConfig() {
            @Override
            public Gh gh() {
                return new Gh() {
                    @Override
                    public String repo() {
                        return "quarkusio/quarkus";
                    }

                    @Override
                    public String binary() {
                        return binary;
                    }

                    @Override
                    public Duration timeout() {
                        return timeout;
                    }
                };
            }

            @Override
            public Jev jev() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Path outputDir() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
