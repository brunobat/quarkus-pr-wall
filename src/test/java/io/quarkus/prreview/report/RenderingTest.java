package io.quarkus.prreview.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.output.TokenUsage;
import io.quarkus.prreview.classification.checks.testadequacy.TestAdequacyCheck;
import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.pr.model.ChangedFile;
import io.quarkus.prreview.pr.model.PrContext;
import io.quarkus.prreview.pr.model.PullRequest;
import io.quarkus.prreview.report.markdown.MarkdownReport;
import io.quarkus.prreview.report.summary.FallbackSummary;
import io.quarkus.prreview.report.summary.model.Summary;
import io.quarkus.prreview.report.terminal.TerminalRenderer;
import io.quarkus.prreview.triage.model.PrOutcome;

class RenderingTest {

    static final Summary CLAUDE = new Summary("Runtime behaviour changes; no tests were changed.", "claude-opus-5-5",
            1234, new TokenUsage(300, 25));
    static final Path REPORT = Path.of("reports/review-57720.md");

    static Classification classification(String title, String path, double behaviour, String chosen,
            Map<String, Double> options) {
        PullRequest pr = new PullRequest(57720, title, "https://github.com/quarkusio/quarkus/pull/57720", "OPEN", "");
        List<ChangedFile> files = List.of(new ChangedFile(path, ChangedFile.Kind.MODIFIED, null, false));
        CheckResult check = new CheckResult("test-adequacy", List.of(
                new QuestionResult("test-adequacy", "behaviour", "behaviour", TestAdequacyCheck.BEHAVIOUR.text(),
                        new QuestionResult.YesNo(behaviour)),
                new QuestionResult("test-adequacy", "tests", "tests", TestAdequacyCheck.TESTS.text(),
                        new QuestionResult.Choice(chosen, TestAdequacyCheck.TESTS.options(), options))));
        return new Classification(new PrContext(pr, files), List.of(check), "jev-1", new TokenUsage(1200, 40), 5000,
                60000);
    }

    static Classification sample(double behaviour, String chosen, Map<String, Double> options) {
        return classification("Bump jackson-bom", "pom.xml", behaviour, chosen, options);
    }

    /** The terminal block for a classified PR whose report was written to {@link #REPORT}. */
    static String terminal(Classification classification, Summary summary) {
        return TerminalRenderer.render(new PrOutcome.Classified(classification, summary),
                new ReportWriter.Written(REPORT));
    }

    static PrOutcome.NotClassified notClassified(PullRequest pr, String reason, String message) {
        return new PrOutcome.NotClassified(57715, List.of("test-adequacy"), pr, reason, message, "jev-latest", 61234,
                60000);
    }

    static Map<String, Double> probs(Object... pairs) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Double) pairs[i + 1]);
        }
        return map;
    }

    @Test
    void terminalBlockMatchesTheSpecLayout() {
        String block = terminal(sample(0.88, "no-test-changes",
                probs("no-test-changes", 0.64, "multiple-gaps", 0.36)), CLAUDE);

        assertEquals("""
                PR #57720 - test adequacy
                  behaviour 0.88  tests: no-test-changes 0.64
                Summary (Claude): Runtime behaviour changes; no tests were changed.
                Report: reports/review-57720.md
                """, block);
    }

    @Test
    void probabilitiesAreOnlyShownNeverInterpreted() {
        String reference = null;
        for (double p : new double[] { 0.0, 0.4999, 0.5, 1.0, 0.88 }) {
            Classification c = sample(p, "no-test-changes", probs("no-test-changes", p));
            String terminal = terminal(c, CLAUDE);
            String report = MarkdownReport.render(c, CLAUDE);

            assertTrue(terminal.contains("behaviour " + AnswerFormat.rounded(p) + "  "), terminal);
            assertTrue(report.contains("| " + AnswerFormat.exact(p) + " |"), report);
            // Apart from the numbers, the output is the same for every value.
            String normalized = (terminal + report).replace(AnswerFormat.rounded(p), "P").replace(AnswerFormat.exact(p), "P");
            if (reference == null) {
                reference = normalized;
            }
            assertEquals(reference, normalized);
        }
        assertEquals("0.50", AnswerFormat.rounded(0.4999));
        assertEquals("0.4999", AnswerFormat.exact(0.4999));
        assertEquals("0.0001", AnswerFormat.exact(0.0001));
    }

    @Test
    void shownOptionIsJevsChoiceEvenWhenAnotherIsMoreLikely() {
        Classification c = sample(0.5, "undetermined", probs("fully-exercised", 0.6, "undetermined", 0.3));

        assertTrue(terminal(c, CLAUDE).contains("tests: undetermined 0.30"));
        String report = MarkdownReport.render(c, CLAUDE);
        assertTrue(report.contains("jev's choice: `undetermined`"), report);
        assertTrue(report.contains("| `fully-exercised` | " + TestAdequacyCheck.TESTS.options().get("fully-exercised")
                + " | 0.6 |"), report);
        assertTrue(report.contains("| `no-test-changes` | " + TestAdequacyCheck.TESTS.options().get("no-test-changes")
                + " | not reported |"), report);
    }

    @Test
    void choiceWithoutProbabilitiesShowsTheOptionOnly() {
        Classification c = sample(0.5, "no-test-changes", Map.of());

        assertTrue(terminal(c, CLAUDE).contains("  tests: no-test-changes\n"));
        assertEquals("jev classification: behaviour 0.50; tests no-test-changes.", FallbackSummary.of(c).text());
    }

    @Test
    void reportSectionsAppearInOrderWithEveryOption() {
        String report = MarkdownReport.render(sample(0.88, "no-test-changes", probs("no-test-changes", 0.64)), CLAUDE);

        assertTrue(report.startsWith("# PR #57720: Bump jackson-bom\n\nLink: https://github.com/quarkusio/quarkus/pull/57720"
                + " · State: OPEN\n"), report);
        int classification = report.indexOf("\n## Classification\n");
        int summary = report.indexOf("\n## Summary\n");
        int files = report.indexOf("\n## Changed files\n");
        int run = report.indexOf("\n## Run\n");
        assertTrue(0 < classification && classification < summary && summary < files && files < run, report);
        for (String option : TestAdequacyCheck.TESTS.options().keySet()) {
            assertTrue(report.contains("| `" + option + "` |"), option);
        }
        assertTrue(report.contains("**Claude (claude-opus-5-5):** Runtime behaviour changes; no tests were changed.\n"),
                report);
        assertTrue(report.contains("- `pom.xml` (modified)"), report);
        assertTrue(report.endsWith("""
                ## Run

                - jev model: jev-1
                - Submission size: 5000 characters (limit 60000)
                - jev tokens: 1200 input, 40 output
                - Summary: Claude (claude-opus-5-5)
                - Claude submission: 1234 characters (system and user messages)
                - Claude tokens: 300 input, 25 output
                """), report);
    }

    @Test
    void reportFooterSaysWhenClaudeWasNotCalledOrReportedNoTokens() {
        Classification c = sample(0.88, "no-test-changes", probs("no-test-changes", 0.64));
        String notSent = MarkdownReport.render(c, FallbackSummary.of(c));
        assertTrue(notSent.endsWith("- Summary: fallback template\n- Claude submission: not sent\n"
                + "- Claude tokens: not reported\n"), notSent);

        String failed = MarkdownReport.render(c, new Summary(FallbackSummary.of(c).text(), null, 987, null));
        assertTrue(failed.endsWith("- Summary: fallback template\n"
                + "- Claude submission: 987 characters (system and user messages)\n"
                + "- Claude tokens: not reported\n"), failed);
    }

    @Test
    void untrustedTextCannotChangeTheStructure() {
        Classification c = classification("Fix | pipes\n## Injected\n| a | b |", "docs/a|b `x`.adoc", 0.5,
                "no-test-changes", probs("no-test-changes", 0.5));
        Summary summary = new Summary("Line one.\n# Heading\n| x |", "model|x");

        String report = MarkdownReport.render(c, summary);

        assertTrue(report.startsWith("# PR #57720: Fix | pipes ## Injected | a | b |\n"), report);
        assertTrue(report.contains("- `` docs/a|b `x`.adoc `` (modified)"), report);
        assertTrue(report.contains("**Claude (model|x):** Line one. # Heading | x |\n"), report);
        assertFalse(report.lines().anyMatch(l -> l.startsWith("## Injected") || l.startsWith("# Heading")), report);
        // Only the renderer's own sections are headings.
        assertEquals(List.of("# PR #57720: Fix | pipes ## Injected | a | b |", "## Classification", "### tests",
                "## Summary", "## Changed files", "## Run"),
                report.lines().filter(l -> l.startsWith("#")).toList());
        // Every table row has exactly its columns: a '|' from text is escaped.
        report.lines().filter(l -> l.startsWith("| ")).forEach(
                l -> assertEquals(4, l.replace("\\|", "").chars().filter(ch -> ch == '|').count(), l));
    }

    @Test
    void untrustedTextNeverStartsALine() {
        PullRequest pr = new PullRequest(1, "t", "> quote", "- list", "");
        Classification c = new Classification(new PrContext(pr, List.of()), sample(0.5, "undetermined",
                probs("undetermined", 0.5)).checks(), "jev", null, 1, 2);
        for (String text : List.of("# Heading", "```", "> quote", "- item", "1. item", "| a | b |", "~~~")) {
            String report = MarkdownReport.render(c, new Summary(text + "\n## Run\nmore", "m"));

            // Every line of the report starts with the renderer's own text.
            report.lines().filter(l -> !l.isEmpty()).forEach(l -> assertTrue(
                    l.startsWith("#") || l.startsWith("Link: ") || l.startsWith("|") || l.startsWith("Question: ")
                            || l.startsWith("jev's choice: ") || l.startsWith("**") || l.startsWith("- "),
                    l));
            assertEquals(1, report.lines().filter(l -> l.equals("## Run")).count(), report);
            assertTrue(report.contains("\n**Claude (m):** " + text + " ## Run more\n"), report);
        }
        // GitHub text on the link line follows the fixed label too.
        String report = MarkdownReport.render(c, new Summary("x", "m"));
        assertTrue(report.contains("Link: > quote · State: - list\n"), report);
    }

    @Test
    void fallbackTemplateListsEveryAnswerInOrder() {
        Summary fallback = FallbackSummary.of(sample(0.88, "no-test-changes", probs("no-test-changes", 0.64)));

        assertEquals("jev classification: behaviour 0.88; tests no-test-changes 0.64.", fallback.text());
        assertEquals("fallback template", fallback.source());
        assertTrue(terminal(sample(0.88, "no-test-changes", probs("no-test-changes", 0.64)), fallback).contains("Summary (fallback template): jev classification: behaviour 0.88;"));
    }

    @Test
    void notClassifiedTerminalBlockMatchesTheSpecLayout() {
        PrOutcome.NotClassified outcome = notClassified(null, "jev: {\"error\":\n \"max_tokens_exceeded\"}", "body");

        assertEquals("""
                PR #57715 - test adequacy
                  not classified: jev: {"error": "max_tokens_exceeded"}
                Report: reports/review-57720.md
                """, TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT)));
        assertEquals("""
                PR #57715 - test adequacy
                  not classified: jev: {"error": "max_tokens_exceeded"}
                Report: not written: FileSystemException: /x: Not a directory
                """, TerminalRenderer.render(outcome, new ReportWriter.Failed(REPORT,
                "FileSystemException: /x: Not a directory")));
    }

    @Test
    void reportNotWrittenAlsoEndsAClassifiedBlock() {
        String block = TerminalRenderer.render(new PrOutcome.Classified(sample(0.88, "no-test-changes",
                probs("no-test-changes", 0.64)), CLAUDE), new ReportWriter.Failed(REPORT, "AccessDeniedException: x"));

        assertTrue(block.endsWith("Summary (Claude): Runtime behaviour changes; no tests were changed.\n"
                + "Report: not written: AccessDeniedException: x\n"), block);
    }

    @Test
    void errorReportWithATitle() {
        PullRequest pr = new PullRequest(57715, "Big | change", "https://github.com/quarkusio/quarkus/pull/57715",
                "OPEN", "");
        String report = MarkdownReport.render(notClassified(pr, "submission 61234 chars exceeds limit 60000",
                "submission is 61234 characters,\nover the limit of 60000"));

        assertEquals("""
                # PR #57715: Big | change

                Link: https://github.com/quarkusio/quarkus/pull/57715 · State: OPEN

                ## Not classified

                Reason: submission 61234 chars exceeds limit 60000

                Message: submission is 61234 characters, over the limit of 60000

                ## Run

                - jev model: jev-latest
                - Submission size: 61234 characters (limit 60000)
                """, report);
        assertFalse(report.contains("## Classification"));
        assertFalse(report.contains("## Summary"));
    }

    @Test
    void errorReportWithoutATitleShowsOnlyWhatIsKnown() {
        PrOutcome.NotClassified outcome = new PrOutcome.NotClassified(404, List.of("test-adequacy"), null,
                "gh: no pull requests found", "no pull requests found\n## Run\n<!-- hidden", null, null, null);

        assertEquals("""
                # PR #404

                ## Not classified

                Reason: gh: no pull requests found

                Message: no pull requests found ## Run &lt;!-- hidden

                ## Run

                - jev model: not reached
                """, MarkdownReport.render(outcome));
    }
}
