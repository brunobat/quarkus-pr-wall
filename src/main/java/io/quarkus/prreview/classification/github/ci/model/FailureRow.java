package io.quarkus.prreview.classification.github.ci.model;

/**
 * One row of a not-passed check: a failing test, or what is known instead.
 *
 * @param test the failing test's name, or {@code null} for a row without a test
 * @param flaky whether the test is also listed as flaky, or {@code null} for a row without a test
 * @param whatFailed the error text, or why there are no details
 * @param testLink the test's history link, or {@code null}
 * @param scanLink the job's build scan link, or {@code null}
 */
public record FailureRow(String test, Boolean flaky, String whatFailed, String testLink, String scanLink) {

    public static final String DETAILS_UNAVAILABLE = "details unavailable";
    public static final String NO_SOURCE = "no source for failure details";

    /** A row with no test, saying only {@code text}. */
    public static FailureRow withoutTest(String text) {
        return new FailureRow(null, null, text, null, null);
    }
}
