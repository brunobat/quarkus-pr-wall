package io.quarkus.prreview.classification.github.model;

/**
 * A test quarkus-bot lists as flaky on the head commit: it failed, then passed on retry, in a job.
 *
 * @param job the job's name, as the bot gives it
 * @param workflow the workflow the job belongs to
 * @param test the test's name
 * @param module the module path the bot lists the test under, or empty
 * @param historyLink the test's Develocity history link
 */
public record FlakyTest(String job, String workflow, String test, String module, String historyLink) {
}
