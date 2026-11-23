package io.quarkus.prreview.report;

import java.util.List;

import io.quarkus.prreview.classification.github.model.Approval;
import io.quarkus.prreview.classification.github.model.CiCheck;
import io.quarkus.prreview.classification.github.model.CiChecks;
import io.quarkus.prreview.classification.github.model.FailureRow;
import io.quarkus.prreview.classification.github.model.FlakyTest;

/** Wording shared by the terminal block and the markdown report for a PR's checks. */
public final class CiFormat {

    public static final String UNAVAILABLE = "unavailable";

    public static final String[] COLUMNS = { "Check", "Result", "Test", "Flaky", "What failed" };

    /** The flaky list's columns in the terminal; the report adds {@code Module}. */
    public static final String[] FLAKY_COLUMNS = { "Job", "Test" };

    private CiFormat() {
    }

    /**
     * The CI line after {@code CI: }: the counts ({@code 8 of 67 not passed}, {@code all 67 passed, skipped or
     * neutral}, or {@code no workflow runs on the head commit}), led by {@code workflows waiting for maintainer
     * approval} when GitHub marks a suite so, and followed by {@code (approval status unavailable)} when the suites
     * could not be read.
     */
    public static String count(CiChecks ci) {
        int total = ci.checks().size();
        if (ci.approval() == Approval.WAITING) {
            return "workflows waiting for maintainer approval; "
                    + (total == 0 ? "no checks ran" : "only " + total + " checks ran (" + counts(ci) + ")");
        }
        String counts = total == 0 ? "no workflow runs on the head commit" : counts(ci);
        return ci.approval() == Approval.UNAVAILABLE ? counts + " (approval status unavailable)" : counts;
    }

    private static String counts(CiChecks ci) {
        int total = ci.checks().size();
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

    /**
     * What follows {@code Flaky: }: {@code 4 tests in 3 jobs (passed on retry)}, {@code unavailable} when the comments
     * could not be read, or {@code null} when there is no flaky test and so no line.
     */
    public static String flaky(CiChecks ci) {
        if (ci.flaky() == null) {
            return UNAVAILABLE;
        }
        if (ci.flaky().isEmpty()) {
            return null;
        }
        long jobs = ci.flaky().stream().map(f -> List.of(f.job(), f.workflow())).distinct().count();
        int tests = ci.flaky().size();
        return tests + (tests == 1 ? " test" : " tests") + " in " + jobs + (jobs == 1 ? " job" : " jobs")
                + " (passed on retry)";
    }

    /** The job's name with its workflow, as the failure table's Check column shows a check. */
    public static String job(FlakyTest flaky) {
        return flaky.workflow().isEmpty() ? flaky.job() : flaky.job() + " (" + flaky.workflow() + ")";
    }

    /** {@code yes}, {@code no}, or empty for a row without a test. */
    public static String flaky(FailureRow row) {
        return row.flaky() == null ? "" : row.flaky() ? "yes" : "no";
    }
}
