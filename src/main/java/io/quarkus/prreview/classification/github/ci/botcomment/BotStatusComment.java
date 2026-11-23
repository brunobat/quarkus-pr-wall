package io.quarkus.prreview.classification.github.ci.botcomment;

import java.util.List;
import java.util.Map;

/**
 * What one of quarkus-bot's workflow status comments says about one workflow run.
 *
 * @param workflow the workflow's name, e.g. {@code Quarkus CI}
 * @param commit the full SHA of the commit the run was for
 * @param createdAt when the comment was created, ISO-8601
 * @param runId the workflow run the comment reports on, from its {@code Build-Reporter/workflow-run-id} marker, or
 *        {@code null} when there is none
 * @param failures by job name, in the comment's order: the entries under {@code ## Failures}
 * @param flaky the tests listed under {@code ## Flaky tests}, in the comment's order, once per job and test
 * @param scanLinks by job name: the build scan link from the {@code ## Failing Jobs} table, when there is one
 */
public record BotStatusComment(String workflow, String commit, String createdAt, String runId,
        Map<String, List<Entry>> failures, List<FlakyTest> flaky, Map<String, String> scanLinks) {

    /** Whether the bot lists {@code test} as flaky under {@code job}. */
    public boolean isFlaky(String job, String test) {
        return flaky.stream().anyMatch(f -> f.job().equals(job) && f.test().equals(test));
    }

    /**
     * One failure the bot lists under a job.
     *
     * @param test the test's name, or {@code null} when the failure is not a test (e.g. "Failed to execute goal ...")
     * @param text the error text for a test, or the failure's own text otherwise; may be empty
     * @param historyLink the test's Develocity history link, or {@code null}
     */
    public record Entry(String test, String text, String historyLink) {
    }

    /**
     * A test the bot lists as flaky under a job: it failed, then passed on retry.
     *
     * @param job the job's name
     * @param test the test's name
     * @param module the module path of the {@code #### :package:} line the test is listed under, or empty
     * @param historyLink the test's Develocity history link
     */
    public record FlakyTest(String job, String test, String module, String historyLink) {
    }
}
