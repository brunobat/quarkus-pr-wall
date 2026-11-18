package io.quarkus.prreview.report.markdown;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.langchain4j.model.output.TokenUsage;

import io.quarkus.prreview.classification.github.ci.model.CiCheck;
import io.quarkus.prreview.classification.github.ci.model.CiChecks;
import io.quarkus.prreview.classification.github.ci.model.FailedCheck;
import io.quarkus.prreview.classification.github.ci.model.FailureRow;
import io.quarkus.prreview.classification.github.size.model.PrSize;
import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.JevResult;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.pr.model.ChangedFile;
import io.quarkus.prreview.pr.model.PullRequest;
import io.quarkus.prreview.report.AnswerFormat;
import io.quarkus.prreview.report.CiFormat;
import io.quarkus.prreview.report.summary.model.Summary;
import io.quarkus.prreview.triage.model.PrOutcome;

/**
 * The markdown report for one PR. Classified: title, Classification, Summary, Changed files, Run, with probabilities
 * written as returned. Not classified: title, Not classified (reason and message), Run with the values known by
 * then; no probabilities and no summary. Text from GitHub, {@code jev} or Claude never starts a line: it follows a fixed label on its
 * line, is kept on that one line, is escaped in table cells, and has {@code &} and {@code <} escaped outside code
 * spans (GitHub shows code spans literally), so it cannot add headings, quotes, lists, code blocks, rows, sections
 * or HTML (such as a comment that hides the rest of the report).
 */
public final class MarkdownReport {

    /** A {@code |} with the backslashes right before it. */
    private static final Pattern PIPE = Pattern.compile("(\\\\*)\\|");

    private MarkdownReport() {
    }

    /** The whole report: the title, then each classification source's part in a fixed order. */
    public static String render(PrOutcome outcome) {
        StringBuilder md = new StringBuilder();
        appendTitle(md, outcome.number(), outcome.pullRequest());
        appendSize(md, outcome.size());
        appendCi(md, outcome.ci());
        switch (outcome.jev()) {
            case JevResult.Answered answered -> appendClassified(md, answered.classification(), answered.summary());
            case JevResult.NotAnswered notAnswered -> appendNotClassified(md, notAnswered);
        }
        return md.toString();
    }

    /** The report for a classified PR whose GitHub facts were not gathered. */
    public static String render(Classification classification, Summary summary) {
        return render(PrOutcome.answered(classification, summary));
    }

    private static void appendSize(StringBuilder md, SourceResult<PrSize> size) {
        md.append("## Size (GitHub)\n\n");
        switch (size) {
            case SourceResult.Available<PrSize> available -> md
                    .append("- Changed files: ").append(available.facts().changedFiles()).append("\n")
                    .append("- Lines added: ").append(available.facts().additions()).append("\n")
                    .append("- Lines deleted: ").append(available.facts().deletions()).append("\n");
            case SourceResult.Unavailable<PrSize> unavailable -> md.append("unavailable\n");
        }
        md.append("\n");
    }

    /** The count line, then a table of the checks that did not pass, linking only to {@code https://} URLs. */
    private static void appendCi(StringBuilder md, SourceResult<CiChecks> ci) {
        md.append("## CI checks (GitHub)\n\n");
        switch (ci) {
            case SourceResult.Unavailable<CiChecks> unavailable -> md.append(CiFormat.UNAVAILABLE).append("\n");
            case SourceResult.Available<CiChecks> available -> appendCiTable(md, available.facts());
        }
        md.append("\n");
    }

    private static void appendCiTable(StringBuilder md, CiChecks ci) {
        String count = CiFormat.count(ci);
        if (ci.notPassed().isEmpty()) {
            md.append(ci.checks().isEmpty() ? count : Character.toUpperCase(count.charAt(0)) + count.substring(1) + ".")
                    .append("\n");
            return;
        }
        md.append(count).append(".\n\n");
        md.append("| ").append(String.join(" | ", CiFormat.COLUMNS)).append(" |\n|---|---|---|---|---|\n");
        for (FailedCheck failed : ci.notPassed()) {
            CiCheck check = failed.check();
            String suffix = CiFormat.suffix(check);
            for (FailureRow row : failed.rows()) {
                md.append("| ").append(link(check.name(), check.link()))
                        .append(suffix.isEmpty() ? "" : " " + text(suffix))
                        .append(" | ").append(escapePipes(code(check.value())))
                        .append(" | ").append(row.test() == null ? "" : linkedCode(row.test(), row.testLink()))
                        .append(" | ").append(CiFormat.flaky(row))
                        .append(" | ").append(text(row.whatFailed()))
                        .append(row.scanLink() != null && isLinkable(row.scanLink())
                                ? " · [scan](" + row.scanLink() + ")" : "")
                        .append(" |\n");
            }
        }
    }

    /** Text from GitHub or a bot comment as a table cell that can't form a link: see {@link #cell(String)}. */
    private static String text(String text) {
        return cell(text).replace("[", "\\[").replace("]", "\\]");
    }

    /** {@code text} as a table cell, linked to {@code url} when it is an {@code https://} URL. */
    private static String link(String text, String url) {
        String label = text(text);
        return isLinkable(url) ? "[" + label + "](" + url + ")" : label;
    }

    /** {@code text} as a code span in a table cell, linked to {@code url} when it is an {@code https://} URL. */
    private static String linkedCode(String text, String url) {
        String label = escapePipes(code(text));
        return isLinkable(url) ? "[" + label + "](" + url + ")" : label;
    }

    /** An {@code https://} URL with nothing that could end the link or the table cell early. */
    private static boolean isLinkable(String url) {
        return url != null && url.startsWith("https://") && url.chars().noneMatch(
                c -> Character.isWhitespace(c) || "()<>[]|`\"'\\".indexOf(c) >= 0);
    }

    private static void appendClassified(StringBuilder md, Classification classification, Summary summary) {
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
        md.append("- Project context: ").append(inline(classification.projectContext().source())).append(" (")
                .append(classification.projectContext().text().length()).append(" characters)\n");
        md.append("- jev tokens: ").append(tokens(classification.tokenUsage())).append("\n");
        md.append("- Summary: ").append(inline(summary.source())).append("\n");
        md.append("- Claude submission: ").append(summary.claudeChars() == null ? "not sent"
                : summary.claudeChars() + " characters (system and user messages)").append("\n");
        md.append("- Claude tokens: ").append(tokens(summary.claudeTokens())).append("\n");
    }

    private static void appendNotClassified(StringBuilder md, JevResult.NotAnswered outcome) {
        md.append("## Not classified\n\n");
        md.append("Reason: ").append(inline(outcome.reason())).append("\n\n");
        md.append("Message: ").append(inline(outcome.message())).append("\n");

        md.append("\n## Run\n\n");
        md.append("- jev model: ").append(outcome.jevModel() == null ? "not reached" : inline(outcome.jevModel()))
                .append("\n");
        if (outcome.submissionChars() != null) {
            md.append("- Submission size: ").append(outcome.submissionChars()).append(" characters (limit ")
                    .append(outcome.limit()).append(")\n");
        }
    }

    /** {@code # PR #<n>: <title>} and the link line, or just {@code # PR #<n>} when the PR was not fetched. */
    private static void appendTitle(StringBuilder md, int number, PullRequest pr) {
        if (pr == null) {
            md.append("# PR #").append(number).append("\n\n");
            return;
        }
        md.append("# PR #").append(number).append(": ").append(inline(pr.title())).append("\n\n");
        md.append("Link: ").append(inline(pr.url())).append(" · State: ").append(inline(pr.state())).append("\n\n");
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
     * {@code <} outside code spans, so it cannot open HTML. Display is unchanged: GitHub shows {@code &lt;} as
     * {@code <}, and code spans, which it shows literally, are left as they are.
     */
    static String inline(String text) {
        String line = oneLine(text);
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(i + 1);
                if (next == '`' || next == '\\') {
                    // An escaped backtick opens no code span; an escaped backslash escapes nothing after it.
                    out.append(c).append(next);
                    i += 2;
                } else if (next == '&' || next == '<') {
                    // The backslash only kept the character literal, which the entity now does.
                    i++;
                } else {
                    out.append(c);
                    i++;
                }
            } else if (c == '`') {
                int run = backtickRun(line, i);
                int close = closingRun(line, i + run, run);
                int end = close < 0 ? i + run : close + run;
                // A code span is kept as is; an unmatched run is literal backticks.
                out.append(line, i, end);
                i = end;
            } else {
                out.append(c == '&' ? "&amp;" : c == '<' ? "&lt;" : String.valueOf(c));
                i++;
            }
        }
        return out.toString();
    }

    private static int backtickRun(String text, int from) {
        int end = from;
        while (end < text.length() && text.charAt(end) == '`') {
            end++;
        }
        return end - from;
    }

    /** Where the next run of exactly {@code length} backticks starts, at or after {@code from}, or -1. */
    private static int closingRun(String text, int from, int length) {
        int i = from;
        while (i < text.length()) {
            if (text.charAt(i) == '`') {
                int run = backtickRun(text, i);
                if (run == length) {
                    return i;
                }
                i += run;
            } else {
                i++;
            }
        }
        return -1;
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
     * Inline code, fenced by one backtick more than the longest run of backticks in the text, so the text cannot
     * close it. Not HTML-escaped: GitHub shows code spans literally, so an entity there would be displayed as typed.
     */
    static String code(String text) {
        String value = oneLine(text);
        int longest = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '`') {
                int run = backtickRun(value, i);
                longest = Math.max(longest, run);
                i += run - 1;
            }
        }
        String fence = "`".repeat(longest + 1);
        // Spaces keep a backtick at either end of the text from joining the fence; GitHub strips one on each side.
        return longest == 0 ? fence + value + fence : fence + " " + value + " " + fence;
    }
}
