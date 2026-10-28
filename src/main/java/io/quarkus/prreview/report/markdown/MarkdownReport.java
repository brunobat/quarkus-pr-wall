package io.quarkus.prreview.report.markdown;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * written as returned. Text from GitHub, {@code jev} or Claude never starts a line: it follows a fixed label on its
 * line, is kept on that one line, is escaped in table cells, and has {@code &} and {@code <} escaped outside code
 * spans, so it cannot add headings, quotes, lists, code blocks, rows, sections or HTML (such as a comment that hides
 * the rest of the report).
 */
public final class MarkdownReport {

    /** A {@code |} with the backslashes right before it. */
    private static final Pattern PIPE = Pattern.compile("(\\\\*)\\|");

    private MarkdownReport() {
    }

    public static String render(Classification classification, Summary summary) {
        PullRequest pr = classification.pr().pullRequest();
        StringBuilder md = new StringBuilder();
        md.append("# PR #").append(pr.number()).append(": ").append(inline(pr.title())).append("\n\n");
        md.append("Link: ").append(inline(pr.url())).append(" · State: ").append(inline(pr.state())).append("\n\n");

        md.append("## Classification\n");
        for (CheckResult check : classification.checks()) {
            appendYesNo(md, check);
            for (QuestionResult question : check.questions()) {
                if (question.answer() instanceof QuestionResult.Choice choice) {
                    appendChoice(md, question, choice);
                }
            }
        }

        md.append("\n## Summary\n\n").append("**").append(inline(summary.source())).append(":** ")
                .append(inline(summary.text())).append("\n");

        md.append("\n## Changed files\n\n");
        for (ChangedFile file : classification.pr().changedFiles()) {
            md.append("- ").append(code(file.path())).append(" (").append(inline(file.describe())).append(")\n");
        }

        md.append("\n## Run\n\n");
        md.append("- jev model: ").append(classification.jevModel() == null ? "not reported"
                : inline(classification.jevModel())).append("\n");
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
                .append("Question: ").append(inline(question.text())).append("\n\n")
                .append("jev's choice: ").append(code(choice.value())).append("\n\n")
                .append("| Option | Description | Probability |\n|---|---|---|\n");
        for (Map.Entry<String, String> option : choice.options().entrySet()) {
            Double p = choice.probabilities().get(option.getKey());
            md.append("| ").append(escapePipes(code(option.getKey()))).append(" | ").append(cell(option.getValue()))
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

    /**
     * Keeps text on one line, so it cannot start a heading, list item or table row, and escapes {@code &} and
     * {@code <}, so it cannot open HTML. Display is unchanged: GitHub shows {@code &lt;} as {@code <}.
     */
    static String inline(String text) {
        return oneLine(text).replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    /** One-line text inside a table cell, see {@link #escapePipes(String)}. */
    static String cell(String text) {
        return escapePipes(inline(text));
    }

    /**
     * Escapes {@code |}. Backslashes right before a {@code |} are escaped too, so {@code a\\|b} cannot turn the pipe
     * back into a column separator.
     */
    private static String escapePipes(String text) {
        return PIPE.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1).repeat(2) + "\\|"));
    }

    /**
     * Inline code, using a longer fence when the text itself contains a backtick. Not HTML-escaped: GitHub shows
     * code spans literally, so an entity there would be displayed as typed.
     */
    static String code(String text) {
        String value = oneLine(text);
        return value.contains("`") ? "`` " + value + " ``" : "`" + value + "`";
    }
}
