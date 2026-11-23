package io.quarkus.prreview.report;

import java.util.ArrayList;
import java.util.List;

import io.quarkus.prreview.classification.github.model.CiChecks;
import io.quarkus.prreview.classification.github.model.FailedCheck;
import io.quarkus.prreview.classification.github.model.FailureRow;
import io.quarkus.prreview.classification.github.model.FlakyTest;
import io.quarkus.prreview.classification.github.model.PrSize;
import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.JevResult;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.classification.model.SourceResult;
import io.quarkus.prreview.classification.provenance.model.Provenance;
import io.quarkus.prreview.report.summary.Summary;
import io.quarkus.prreview.triage.model.PrOutcome;

/**
 * The terminal block for one PR, classified or not:
 *
 * <pre>
 * PR #57720 - test adequacy
 *   behaviour 0.88  tests-changed 0.08  reach: none 0.83  assert: nothing-executed 0.88
 * Summary (Claude): ...
 * Report: reports/review-57720.md
 *
 * PR #57715 - test adequacy
 *   not classified: jev: ...
 * Report: not written: ...
 * </pre>
 */
public final class TerminalRenderer {

    /** The most characters of a failure's text shown on its one line. */
    static final int WHAT_FAILED_WIDTH = 120;

    private TerminalRenderer() {
    }

    /** The whole block for a number GitHub says is not a pull request in {@code repo}: one line. */
    public static String notAPullRequest(int number, String repo) {
        return "PR #" + number + ": not a pull request in " + PlainText.oneLine(repo) + "\n";
    }

    /**
     * The whole block for a PR whose processing ended with an unexpected exception: the header, the exception's class
     * and one-line message, and no report.
     */
    public static String unexpectedError(int number, List<String> checkIds, Throwable error) {
        String message = error.getMessage() == null ? "" : ": " + PlainText.oneLine(error.getMessage());
        return header(number, checkIds, "") + "  failed: unexpected error: " + error.getClass().getSimpleName()
                + message + "\n" + "Report: not written: unexpected error\n";
    }

    /** The whole block: the header, then each classification source's part in a fixed order, then the report line. */
    public static String render(PrOutcome outcome, ReportWriter.Result report) {
        return header(outcome) + jev(outcome.jev()) + size(outcome.size()) + ci(outcome.ci())
                + provenance(outcome.provenance()) + switch (report) {
                    case ReportWriter.Written written -> "Report: " + written.path() + "\n";
                    case ReportWriter.Failed failed ->
                        "Report: not written: " + PlainText.oneLine(failed.error()) + "\n";
                };
    }

    private static String jev(JevResult jev) {
        return switch (jev) {
            case JevResult.Answered answered -> classified(answered.classification(), answered.summary());
            case JevResult.NotAnswered notAnswered ->
                "  not classified: " + PlainText.oneLine(notAnswered.reason()) + "\n";
        };
    }

    private static String size(SourceResult<PrSize> size) {
        return "Size: " + switch (size) {
            case SourceResult.Available<PrSize> available -> available.facts().changedFiles() + " files changed, +"
                    + available.facts().additions() + " -" + available.facts().deletions();
            case SourceResult.Unavailable<PrSize> unavailable -> "unavailable";
        } + "\n";
    }

    private static String provenance(SourceResult<Provenance> provenance) {
        return switch (provenance) {
            case SourceResult.Unavailable<Provenance> unavailable -> "Author: " + ProvenanceFormat.UNAVAILABLE;
            case SourceResult.Available<Provenance> available ->
                ProvenanceFormat.line(available.facts(), PlainText::oneLine);
        } + "\n";
    }

    /**
     * The count line, then a table of the checks that did not pass, then the tests the bot lists as flaky, without
     * links.
     */
    private static String ci(SourceResult<CiChecks> ci) {
        return switch (ci) {
            case SourceResult.Unavailable<CiChecks> unavailable -> "CI: " + CiFormat.UNAVAILABLE + "\n";
            case SourceResult.Available<CiChecks> available -> "CI: " + CiFormat.count(available.facts()) + "\n"
                    + table(available.facts()) + flaky(available.facts());
        };
    }

    private static String flaky(CiChecks ci) {
        String line = CiFormat.flaky(ci);
        if (line == null) {
            return "";
        }
        if (ci.flaky() == null) {
            return "Flaky: " + line + "\n";
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(CiFormat.FLAKY_COLUMNS);
        for (FlakyTest flaky : ci.flaky()) {
            rows.add(new String[] { PlainText.oneLine(CiFormat.job(flaky)), PlainText.oneLine(flaky.test()) });
        }
        return "Flaky: " + line + "\n" + aligned(rows);
    }

    private static String table(CiChecks ci) {
        if (ci.notPassed().isEmpty()) {
            return "";
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(CiFormat.COLUMNS);
        for (FailedCheck failed : ci.notPassed()) {
            for (FailureRow row : failed.rows()) {
                rows.add(new String[] { PlainText.oneLine(failed.check().name() + CiFormat.suffix(failed.check())),
                        PlainText.oneLine(failed.check().value()),
                        row.test() == null ? "" : PlainText.oneLine(row.test()),
                        CiFormat.flaky(row), cut(PlainText.oneLine(row.whatFailed())) });
            }
        }
        return aligned(rows);
    }

    /** The rows indented by two spaces, each column but the last padded to its widest cell plus two spaces. */
    private static String aligned(List<String[]> rows) {
        int[] widths = new int[rows.getFirst().length - 1];
        for (String[] row : rows) {
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        StringBuilder table = new StringBuilder();
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder("  ");
            for (int i = 0; i < widths.length; i++) {
                line.append(row[i]).append(" ".repeat(widths[i] - row[i].length() + 2));
            }
            table.append(line.append(row[widths.length]).toString().stripTrailing()).append('\n');
        }
        return table.toString();
    }

    /** At most {@link #WHAT_FAILED_WIDTH} characters, ending in {@code …} when cut. */
    private static String cut(String text) {
        return text.length() <= WHAT_FAILED_WIDTH ? text : text.substring(0, WHAT_FAILED_WIDTH - 1) + "…";
    }

    private static String classified(Classification classification, Summary summary) {
        List<String> answers = new ArrayList<>();
        for (CheckResult check : classification.checks()) {
            for (QuestionResult question : check.questions()) {
                answers.add(switch (question.answer()) {
                    case QuestionResult.YesNo yesNo -> question.label() + " " + AnswerFormat.shortAnswer(yesNo);
                    case QuestionResult.Choice choice -> question.label() + ": " + AnswerFormat.shortAnswer(choice);
                });
            }
        }
        return "  " + String.join("  ", answers) + "\n"
                + "Summary (" + summary.shortSource() + "): " + PlainText.oneLine(summary.text()) + "\n";
    }

    /** The PR line; a draft's carries its state, so it stands out in a list. */
    private static String header(PrOutcome outcome) {
        String draft = outcome.draft() ? " · State: " + PlainText.oneLine(outcome.pullRequest().state()) + " (draft)" : "";
        return header(outcome.number(), outcome.checks(), draft);
    }

    private static String header(int number, List<String> checkIds, String state) {
        String checks = String.join(", ", checkIds.stream().map(id -> id.replace('-', ' ')).toList());
        return "PR #" + number + " - " + checks + state + "\n";
    }
}
