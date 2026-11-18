package io.quarkus.prreview.classification.github.ci.botcomment;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one of quarkus-bot's workflow status comments says about one workflow run.
 *
 * @param workflow the workflow's name, e.g. {@code Quarkus CI}
 * @param commit the full SHA of the commit the run was for
 * @param createdAt when the comment was created, ISO-8601
 * @param failures by job name, in the comment's order: the entries under {@code ## Failures}
 * @param flaky the tests listed under {@code ## Flaky tests}, by job
 * @param scanLinks by job name: the build scan link from the {@code ## Failing Jobs} table, when there is one
 */
public record BotStatusComment(String workflow, String commit, String createdAt, Map<String, List<Entry>> failures,
        Set<FlakyTest> flaky, Map<String, String> scanLinks) {

    /**
     * One failure the bot lists under a job.
     *
     * @param test the test's name, or {@code null} when the failure is not a test (e.g. "Failed to execute goal ...")
     * @param text the error text for a test, or the failure's own text otherwise; may be empty
     * @param historyLink the test's Develocity history link, or {@code null}
     */
    public record Entry(String test, String text, String historyLink) {
    }

    public record FlakyTest(String job, String test) {
    }
}
