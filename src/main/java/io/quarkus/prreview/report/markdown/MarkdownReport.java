package io.quarkus.prreview.report.markdown;

import java.util.Map;

import dev.langchain4j.model.output.TokenUsage;

import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.pr.model.ChangedFile;
import io.quarkus.prreview.pr.model.PullRequest;
import io.quarkus.prreview.report.AnswerFormat;
import io.quarkus.prreview.report.summary.model.Summary;

/**
 * The markdown report for one classified PR: title, Classification, Summary, Changed files, Run. Probabilities are
 * written as returned. Text from GitHub, {@code jev} or Claude is kept on one line and escaped in table cells, so it
 * cannot add headings, rows or sections.
 */
public final class MarkdownReport {

    private MarkdownReport() {
    }

    public static String render(Classification classification, Summary summary) {
        PullRequest pr = classification.pr().pullRequest();
        StringBuilder md = new StringBuilder();
        md.append("# PR #").append(pr.number()).append(": ").append(inline(pr.title())).append("\n\n");
        md.append(inline(pr.url())).append(" · ").append(inline(pr.state())).append("\n\n");

        md.append("## Classification\n");
        for (CheckResult check : classification.checks()) {
            appendYesNo(md, check);
            for (QuestionResult question : check.questions()) {
                if (question.answer() instanceof QuestionResult.Choice choice) {
                    appendChoice(md, question, choice);
                }
            }
        }

        md.append("\n## Summary\n\n").append(inline(summary.text())).append("\n\n")
                .append("Source: ").append(inline(summary.source())).append("\n");

        md.append("\n## Changed files\n\n");
        for (ChangedFile file : classification.pr().changedFiles()) {
            md.append("- ").append(code(file.path())).append(" (").append(inline(file.describe())).append(")\n");
        }

        md.append("\n## Run\n\n");
        md.append("- jev model: ").append(inline(classification.jevModel())).append("\n");
        md.append("- Submission size: ").append(classification.submissionChars()).append(" characters (limit ")
                .append(classification.limit()).append(")\n");
        md.append("- jev tokens: ").append(tokens(classification.tokenUsage())).append("\n");
        md.append("- Summary: ").append(inline(summary.source())).append("\n");
        return md.toString();
    }

    private static void appendYesNo(StringBuilder md, CheckResult check) {
        boolean header = false;
        for (QuestionResult question : check.questions()) {
            if (question.answer() instanceof QuestionResult.YesNo yesNo) {
                if (!header) {
                    md.append("\n| Question | Text | Probability of yes |\n|---|---|---|\n");
                    header = true;
                }
                md.append("| ").append(cell(question.label())).append(" | ").append(cell(question.text())).append(" | ")
                        .append(AnswerFormat.exact(yesNo.probability())).append(" |\n");
            }
        }
    }

    private static void appendChoice(StringBuilder md, QuestionResult question, QuestionResult.Choice choice) {
        md.append("\n### ").append(inline(question.label())).append("\n\n")
                .append(inline(question.text())).append("\n\n")
                .append("jev's choice: ").append(code(choice.value())).append("\n\n")
                .append("| Option | Description | Probability |\n|---|---|---|\n");
        for (Map.Entry<String, String> option : choice.options().entrySet()) {
            Double p = choice.probabilities().get(option.getKey());
            md.append("| ").append(cell(code(option.getKey()))).append(" | ").append(cell(option.getValue()))
                    .append(" | ").append(p == null ? "not reported" : AnswerFormat.exact(p)).append(" |\n");
        }
    }

    private static String tokens(TokenUsage usage) {
        if (usage == null || (usage.inputTokenCount() == null && usage.outputTokenCount() == null)) {
            return "not reported";
        }
        return count(usage.inputTokenCount()) + " input, " + count(usage.outputTokenCount()) + " output";
    }

    private static String count(Integer tokens) {
        return tokens == null ? "not reported" : tokens.toString();
    }

    /** Keeps text on one line, so it cannot start a heading, list item or table row. */
    static String inline(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    /** One-line text inside a table cell, with {@code |} escaped. */
    static String cell(String text) {
        return inline(text).replace("|", "\\|");
    }

    /** Inline code, using a longer fence when the text itself contains a backtick. */
    static String code(String text) {
        String value = inline(text);
        return value.contains("`") ? "`` " + value + " ``" : "`" + value + "`";
    }
}
