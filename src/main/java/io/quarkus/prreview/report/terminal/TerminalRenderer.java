package io.quarkus.prreview.report.terminal;

import java.util.ArrayList;
import java.util.List;

import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.JevResult;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.report.AnswerFormat;
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

    private TerminalRenderer() {
    }

    /** The whole block: the header, then each classification source's part in a fixed order, then the report line. */
    public static String render(PrOutcome outcome, ReportWriter.Result report) {
        return header(outcome.number(), outcome.checks()) + jev(outcome.jev()) + switch (report) {
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
