package io.quarkus.prreview.classification.github.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.github.ci.botcomment.BotCommentParserTest;
import io.quarkus.prreview.classification.github.ci.model.Approval;
import io.quarkus.prreview.classification.github.ci.model.CiChecks;
import io.quarkus.prreview.classification.github.ci.model.FailureRow;
import io.quarkus.prreview.clients.gh.model.GhCheck;
import io.quarkus.prreview.clients.gh.model.GhChecks;
import io.quarkus.prreview.clients.gh.model.GhComment;

class FailureDetailsTest {

    static final String HEAD = BotCommentParserTest.COMMIT_57715;
    static final List<GhComment> COMMENTS = List.of(
            new GhComment("quarkus-bot", "Thanks for your pull request!", "2026-10-21T12:51:43Z"),
            new GhComment("quarkus-bot", BotCommentParserTest.body("2026-failures.md"), "2026-10-22T13:46:09Z"));

    static GhChecks checks(String head) {
        return new GhChecks(head, List.of(
                CiSourceTest.run("JVM Tests - JDK 21", "Quarkus CI", "COMPLETED", "FAILURE"),
                CiSourceTest.run("JVM Integration Tests - JDK 17", "Quarkus CI", "COMPLETED", "FAILURE"),
                // Passed, though the bot lists a flaky test under it.
                CiSourceTest.run("JVM Tests - JDK 17 Windows", "Quarkus CI", "COMPLETED", "SUCCESS"),
                CiSourceTest.run("Native Tests - Security1", "Quarkus CI", "COMPLETED", "CANCELLED"),
                CiSourceTest.run("Docs", "Quarkus Documentation CI", "COMPLETED", "FAILURE"),
                CiSourceTest.status("CI Merge Gate", "FAILURE")));
    }

    static List<List<FailureRow>> rows(CiChecks ci) {
        return ci.notPassed().stream().map(f -> f.rows()).toList();
    }

    static final List<FailureRow> UNAVAILABLE = List.of(FailureRow.withoutTest("details unavailable"));

    @Test
    void failingTestsComeFromTheHeadCommitsCommentWithFlakyAndLinks() {
        List<List<FailureRow>> rows = rows(CiSource.of(checks(HEAD), COMMENTS, Approval.NOT_WAITING));

        String scan = "https://develocity.quarkus.io/s/hzvs2pygcxpvk";
        assertEquals(List.of(
                new FailureRow("io.quarkus.websockets.next.test.maxframesize.MaxFrameSizeTest.testMaxFrameSize", false,
                        "java.util.concurrent.CompletionException: io.netty.channel.StacklessClosedChannelException",
                        "https://develocity.quarkus.io/scans/tests?tests.container=io.quarkus.websockets.next.test"
                                + ".maxframesize.MaxFrameSizeTest&tests.test=testMaxFrameSize",
                        scan),
                new FailureRow("io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test", true,
                        "Timeout waiting for response - java.lang.RuntimeException",
                        "https://develocity.quarkus.io/scans/tests?tests.container=io.quarkus.vertx.http.filters"
                                + ".GracefulShutdownFilterTest&tests.test=test",
                        scan)),
                rows.get(0));
        // A failure that is not a test: no test, no flaky value, and no scan (":construction:").
        FailureRow build = rows.get(1).getFirst();
        assertEquals(1, rows.get(1).size());
        assertEquals(null, build.test());
        assertEquals(null, build.flaky());
        assertEquals(null, build.scanLink());
        // A job the comment doesn't list, a workflow without a comment, and a commit status.
        assertEquals(List.of(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE), rows.subList(2, 5));
    }

    @Test
    void aFlakyTestInAJobThatPassedIsNotShown() {
        CiChecks ci = CiSource.of(checks(HEAD), COMMENTS, Approval.NOT_WAITING);

        assertFalse(ci.notPassed().stream().anyMatch(f -> f.check().name().equals("JVM Tests - JDK 17 Windows")));
        assertFalse(ci.notPassed().stream().flatMap(f -> f.rows().stream())
                .anyMatch(r -> r.test() != null && r.test().contains("CompressionEnabledTestCase")));
    }

    @Test
    void aCommentForAnEarlierCommitIsNeverUsed() {
        List<List<FailureRow>> rows = rows(CiSource.of(checks("ba9690af333f6fbc224371cb62200c3b47b7314a"), COMMENTS,
                Approval.NOT_WAITING));

        rows.forEach(r -> assertEquals(UNAVAILABLE, r));
    }

    @Test
    void withoutAnyQuarkusBotCommentThereIsNoSource() {
        List<List<FailureRow>> rows = rows(CiSource.of(checks(HEAD), List.of(new GhComment("someone", "hi", "x")),
                Approval.NOT_WAITING));

        rows.forEach(r -> assertEquals(List.of(FailureRow.withoutTest("no source for failure details")), r));
    }

    @Test
    void commentsThatCouldNotBeReadLeaveTheChecksAndSayDetailsUnavailable() {
        CiChecks ci = CiSource.of(checks(HEAD), null, Approval.NOT_WAITING);

        assertEquals(6, ci.checks().size());
        assertEquals(5, ci.notPassed().size());
        rows(ci).forEach(r -> assertEquals(UNAVAILABLE, r));
    }
}
