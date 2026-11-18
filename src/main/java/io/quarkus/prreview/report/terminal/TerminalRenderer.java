package io.quarkus.prreview.report.terminal;

import java.util.ArrayList;
import java.util.List;

import io.quarkus.prreview.classification.github.ci.model.CiChecks;
import io.quarkus.prreview.classification.github.ci.model.FailedCheck;
import io.quarkus.prreview.classification.github.ci.model.FailureRow;
import io.quarkus.prreview.classification.github.size.model.PrSize;
import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.JevResult;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.report.AnswerFormat;
import io.quarkus.prreview.report.CiFormat;
import io.quarkus.prreview.report.ReportWriter;
import io.quarkus.prreview.report.summary.model.Summary;
import io.quarkus.prreview.triage.model.PrOutcome;

/**
 * The terminal block for one PR, classified or not:
 *
 * <pre>
 * PR #57720 - test adequacy
 *   behaviour 0.88  tests-changed 0.08  reach: no-test-changes 0.83  assert: no-test-changes 0.88
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

    /** The whole block: the header, then each classification source's part in a fixed order, then the report line. */
    public static String render(PrOutcome outcome, ReportWriter.Result report) {
        return header(outcome.number(), outcome.checks()) + jev(outcome.jev()) + size(outcome.size()) + ci(outcome.ci())
                + switch (report) {
                    case ReportWriter.Written written -> "Report: " + written.path() + "\n";
                    case ReportWriter.Failed failed -> "Report: not written: " + oneLine(failed.error()) + "\n";
                };
    }

    private static String jev(JevResult jev) {
        return switch (jev) {
            case JevResult.Answered answered -> classified(answered.classification(), answered.summary());
            case JevResult.NotAnswered notAnswered -> "  not classified: " + oneLine(notAnswered.reason()) + "\n";
        };
    }

    private static String size(SourceResult<PrSize> size) {
        return "Size: " + switch (size) {
            case SourceResult.Available<PrSize> available -> available.facts().changedFiles() + " files changed, +"
                    + available.facts().additions() + " -" + available.facts().deletions();
            case SourceResult.Unavailable<PrSize> unavailable -> "unavailable";
        } + "\n";
    }

    /** The count line, then a table of the checks that did not pass, without links. */
    private static String ci(SourceResult<CiChecks> ci) {
        return switch (ci) {
            case SourceResult.Unavailable<CiChecks> unavailable -> "CI: " + CiFormat.UNAVAILABLE + "\n";
            case SourceResult.Available<CiChecks> available -> "CI: " + CiFormat.count(available.facts()) + "\n"
                    + table(available.facts());
        };
    }

    private static String table(CiChecks ci) {
        if (ci.notPassed().isEmpty()) {
            return "";
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(CiFormat.COLUMNS);
        for (FailedCheck failed : ci.notPassed()) {
            for (FailureRow row : failed.rows()) {
                rows.add(new String[] { oneLine(failed.check().name() + CiFormat.suffix(failed.check())),
                        oneLine(failed.check().value()), row.test() == null ? "" : oneLine(row.test()),
                        CiFormat.flaky(row), cut(oneLine(row.whatFailed())) });
            }
        }
        int[] widths = new int[CiFormat.COLUMNS.length - 1];
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
                + "Summary (" + summary.shortSource() + "): " + oneLine(summary.text()) + "\n";
    }

    private static String header(int number, List<String> checkIds) {
        return "PR #" + number + " - " + String.join(", ", checkIds.stream().map(id -> id.replace('-', ' ')).toList())
                + "\n";
    }

    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }
}
