package io.quarkus.prreview.report.terminal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.report.AnswerFormat;
import io.quarkus.prreview.report.summary.model.Summary;

/**
 * The terminal block for one classified PR:
 *
 * <pre>
 * PR #57720 – test adequacy
 *   behaviour 0.88  tests: no-test-changes 0.64
 * Summary (Claude): ...
 * Report: reports/review-57720.md
 * </pre>
 */
public final class TerminalRenderer {

    private TerminalRenderer() {
    }

    public static String render(Classification classification, Summary summary, Path report) {
        List<String> checks = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        for (CheckResult check : classification.checks()) {
            checks.add(check.checkId().replace('-', ' '));
            for (QuestionResult question : check.questions()) {
                answers.add(switch (question.answer()) {
                    case QuestionResult.YesNo yesNo -> question.label() + " " + AnswerFormat.shortAnswer(yesNo);
                    case QuestionResult.Choice choice -> question.label() + ": " + AnswerFormat.shortAnswer(choice);
                });
            }
        }
        return "PR #" + classification.pr().pullRequest().number() + " – " + String.join(", ", checks) + "\n"
                + "  " + String.join("  ", answers) + "\n"
                + "Summary (" + summary.shortSource() + "): " + oneLine(summary.text()) + "\n"
                + "Report: " + report + "\n";
    }

    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }
}
