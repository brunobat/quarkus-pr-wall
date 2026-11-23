package io.quarkus.prreview.classification.github.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.github.model.Approval;
import io.quarkus.prreview.classification.github.model.CiCheck;
import io.quarkus.prreview.classification.github.model.CiChecks;
import io.quarkus.prreview.classification.github.model.FailedCheck;
import io.quarkus.prreview.classification.github.model.FailureRow;
import io.quarkus.prreview.classification.github.model.FlakyTest;
import io.quarkus.prreview.clients.gh.model.GhCheck;
import io.quarkus.prreview.clients.gh.model.GhCheckSuite;
import io.quarkus.prreview.clients.gh.model.GhChecks;
import io.quarkus.prreview.clients.gh.model.GhComment;

class CiServiceTest {

    static final String HEAD = "ba9690af0e8bdc5d1c0f7a6c2b6b1e7d3c9a4f21";
    /** quarkus-bot has commented, but there is no status comment for the head commit. */
    static final List<GhComment> WELCOME_ONLY = List.of(new GhComment("quarkus-bot", "Thanks for your pull request!",
            "2026-10-21T12:51:43Z"));

    static GhCheck run(String name, String workflow, String status, String conclusion) {
        return new GhCheck(GhCheck.CHECK_RUN, name, workflow, status, conclusion, "https://github.com/o/r/runs/1",
                null, null, null);
    }

    static GhCheck status(String context, String state) {
        return new GhCheck(GhCheck.STATUS_CONTEXT, null, null, null, null, null, context, state, "");
    }

    @Test
    void listsEveryCheckThatIsNotSuccessSkippedOrNeutralInGitHubsOrder() {
        List<GhCheck> rollup = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rollup.add(run("ok " + i, "Quarkus CI", "COMPLETED", "SUCCESS"));
        }
        for (int i = 0; i < 8; i++) {
            rollup.add(run("skipped " + i, "Quarkus CI", "COMPLETED", "SKIPPED"));
        }
        rollup.add(run("Build scans", "", "COMPLETED", "NEUTRAL"));
        rollup.add(2, run("JVM Tests - JDK 17", "Quarkus CI", "COMPLETED", "FAILURE"));
        rollup.add(10, run("JVM Tests - JDK 25 Semeru", "Quarkus CI", "COMPLETED", "CANCELLED"));
        rollup.add(20, run("Native Tests - Security1", "Quarkus CI", "IN_PROGRESS", ""));
        rollup.add(30, run("Wait for approval", "Quarkus CI", "WAITING", ""));
        rollup.add(40, run("Gradle Tests - JDK 17 Windows", "Quarkus CI", "COMPLETED", "TIMED_OUT"));
        rollup.add(41, run("Docs", "Quarkus Documentation CI", "COMPLETED", "ACTION_REQUIRED"));
        rollup.add(42, run("Odd", "Quarkus CI", "COMPLETED", "SOMETHING_NEW"));
        rollup.add(status("CI Merge Gate", "FAILURE"));

        CiChecks ci = CiService.of(new GhChecks(HEAD, rollup), WELCOME_ONLY, Approval.NOT_WAITING);

        assertEquals(67, ci.checks().size());
        assertEquals(HEAD, ci.headCommit());
        assertEquals(List.of("FAILURE", "CANCELLED", "IN_PROGRESS", "WAITING", "TIMED_OUT", "ACTION_REQUIRED",
                "SOMETHING_NEW", "FAILURE"), ci.notPassed().stream().map(f -> f.check().value()).toList());
        assertEquals(new CiCheck(CiCheck.Kind.STATUS, "CI Merge Gate", "", "FAILURE", ""),
                ci.notPassed().getLast().check());
        for (FailedCheck failed : ci.notPassed()) {
            assertEquals(List.of(FailureRow.withoutTest("details unavailable")), failed.rows());
        }
    }

    @Test
    void successSkippedAndNeutralAreNotListed() {
        CiChecks ci = CiService.of(new GhChecks(HEAD, List.of(run("a", "W", "COMPLETED", "SUCCESS"),
                run("b", "W", "COMPLETED", "SKIPPED"), run("Build scans", "", "COMPLETED", "NEUTRAL"),
                status("CI Merge Gate", "SUCCESS"))), List.of(), Approval.NOT_WAITING);

        assertEquals(4, ci.checks().size());
        assertEquals(List.of(), ci.notPassed());
    }

    @Test
    void noChecksIsAnEmptyList() {
        CiChecks ci = CiService.of(new GhChecks(HEAD, List.of()), List.of(), Approval.NOT_WAITING);

        assertEquals(List.of(), ci.checks());
        assertEquals(List.of(), ci.notPassed());
    }

    @Test
    void aSuiteWithActionRequiredAndNoRunsIsWaitingForApproval() {
        GhCheckSuite awaiting = new GhCheckSuite("completed", "action_required", 0);
        GhCheckSuite passed = new GhCheckSuite("completed", "success", 2);

        assertEquals(Approval.WAITING, CiService.approval(List.of(passed, awaiting)));
        assertEquals(Approval.NOT_WAITING, CiService.approval(List.of()));
        assertEquals(Approval.NOT_WAITING, CiService.approval(List.of(passed)));
        // Only that marking counts: a suite with runs, or one only queued, is not waiting for approval.
        assertEquals(Approval.NOT_WAITING, CiService.approval(List.of(new GhCheckSuite("completed", "action_required", 3))));
        assertEquals(Approval.NOT_WAITING, CiService.approval(List.of(new GhCheckSuite("queued", null, 0),
                new GhCheckSuite("queued", null, 0))));
        assertEquals(Approval.NOT_WAITING, CiService.approval(List.of(new GhCheckSuite("completed", "action_required",
                null))));
    }

    @Test
    void theApprovalStateIsKeptWithTheChecks() {
        for (Approval approval : Approval.values()) {
            assertEquals(approval, CiService.of(new GhChecks(HEAD, List.of()), List.of(), approval).approval());
        }
    }

    @Test
    void flakyTestsComeFromTheHeadCommitsCommentWhenEveryCheckPassed() {
        String head = "a718653ee237e844bbf0d8d0b4c08915a17a9bd4";
        List<GhComment> comments = List.of(new GhComment("quarkus-bot",
                BotCommentParserTest.body("2026-passed-with-flaky.md"), "2026-09-24T23:59:28Z"));
        GhChecks green = new GhChecks(head, List.of(run("JVM Tests - JDK 21 Windows", "Quarkus CI", "COMPLETED",
                "SUCCESS")));

        CiChecks ci = CiService.of(green, comments, Approval.NOT_WAITING);

        assertEquals(List.of(), ci.notPassed());
        assertEquals(2, ci.flaky().size());
        assertEquals(new FlakyTest("JVM Tests - JDK 21 Windows", "Quarkus CI",
                "io.quarkus.micrometer.deployment.binder.VertxTcpMetricsNoClientMetricsTest.testTcpMetricsWithoutClientMetrics",
                "extensions/micrometer/deployment",
                "https://ge.quarkus.io/scans/tests?tests.container=io.quarkus.micrometer.deployment.binder"
                        + ".VertxTcpMetricsNoClientMetricsTest&tests.test=testTcpMetricsWithoutClientMetrics"),
                ci.flaky().getFirst());
        // Only the head commit's comments count; without one there are no flaky tests; unreadable comments are null.
        assertEquals(List.of(), CiService.of(new GhChecks(HEAD, green.rollup()), comments, Approval.NOT_WAITING).flaky());
        assertEquals(List.of(), CiService.of(green, List.of(), Approval.NOT_WAITING).flaky());
        CiChecks unreadable = CiService.of(green, null, Approval.NOT_WAITING);
        assertEquals(null, unreadable.flaky());
        assertEquals(1, unreadable.checks().size());
    }
}
