package io.quarkus.prreview.report;

import io.quarkus.prreview.classification.github.ci.model.CiCheck;
import io.quarkus.prreview.classification.github.ci.model.CiChecks;
import io.quarkus.prreview.classification.github.ci.model.FailureRow;

/** Wording shared by the terminal block and the markdown report for a PR's checks. */
public final class CiFormat {

    public static final String UNAVAILABLE = "unavailable";

    public static final String[] COLUMNS = { "Check", "Result", "Test", "Flaky", "What failed" };

    private CiFormat() {
    }

    /** {@code 8 of 67 not passed}, {@code all 67 passed, skipped or neutral}, or {@code none}: counts only. */
    public static String count(CiChecks ci) {
        int total = ci.checks().size();
        if (total == 0) {
            return "none";
        }
        if (ci.notPassed().isEmpty()) {
            return "all " + total + " passed, skipped or neutral";
        }
        return ci.notPassed().size() + " of " + total + " not passed";
    }

    /** What follows a check's name: {@code (<workflow>)}, {@code (status)}, or nothing for a check run without one. */
    public static String suffix(CiCheck check) {
        if (check.kind() == CiCheck.Kind.STATUS) {
            return " (status)";
        }
        return check.workflow().isEmpty() ? "" : " (" + check.workflow() + ")";
    }

    /** {@code yes}, {@code no}, or empty for a row without a test. */
    public static String flaky(FailureRow row) {
        return row.flaky() == null ? "" : row.flaky() ? "yes" : "no";
    }
}
