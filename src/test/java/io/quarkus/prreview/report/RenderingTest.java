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
import io.quarkus.prreview.clients.gh.model.PullRequest;
import io.quarkus.prreview.pr.model.ChangedFile;
import io.quarkus.prreview.pr.model.PrContext;
import io.quarkus.prreview.report.markdown.MarkdownReport;
import io.quarkus.prreview.report.summary.FallbackSummary;
import io.quarkus.prreview.report.summary.model.Summary;
import io.quarkus.prreview.report.terminal.TerminalRenderer;

class RenderingTest {

    static final Summary CLAUDE = new Summary("Runtime behaviour changes; no tests were changed.", "claude-opus-5-5");
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

    static Map<String, Double> probs(Object... pairs) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Double) pairs[i + 1]);
        }
        return map;
    }

    @Test
    void terminalBlockMatchesTheSpecLayout() {
        String block = TerminalRenderer.render(sample(0.88, "no-test-changes",
                probs("no-test-changes", 0.64, "multiple-gaps", 0.36)), CLAUDE, REPORT);

        assertEquals("""
                PR #57720 – test adequacy
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
            String terminal = TerminalRenderer.render(c, CLAUDE, REPORT);
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

        assertTrue(TerminalRenderer.render(c, CLAUDE, REPORT).contains("tests: undetermined 0.30"));
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

        assertTrue(TerminalRenderer.render(c, CLAUDE, REPORT).contains("  tests: no-test-changes\n"));
        assertEquals("jev classification: behaviour 0.50; tests no-test-changes.", FallbackSummary.of(c).text());
    }

    @Test
    void reportSectionsAppearInOrderWithEveryOption() {
        String report = MarkdownReport.render(sample(0.88, "no-test-changes", probs("no-test-changes", 0.64)), CLAUDE);

        assertTrue(report.startsWith("# PR #57720: Bump jackson-bom\n\nhttps://github.com/quarkusio/quarkus/pull/57720"
                + " · OPEN\n"), report);
        int classification = report.indexOf("\n## Classification\n");
        int summary = report.indexOf("\n## Summary\n");
        int files = report.indexOf("\n## Changed files\n");
        int run = report.indexOf("\n## Run\n");
        assertTrue(0 < classification && classification < summary && summary < files && files < run, report);
        for (String option : TestAdequacyCheck.TESTS.options().keySet()) {
            assertTrue(report.contains("| `" + option + "` |"), option);
        }
        assertTrue(report.contains("Source: Claude (claude-opus-5-5)"), report);
        assertTrue(report.contains("- `pom.xml` (modified)"), report);
        assertTrue(report.endsWith("""
                ## Run

                - jev model: jev-1
                - Submission size: 5000 characters (limit 60000)
                - jev tokens: 1200 input, 40 output
                - Summary: Claude (claude-opus-5-5)
                """), report);
    }

    @Test
    void untrustedTextCannotChangeTheStructure() {
        Classification c = classification("Fix | pipes\n## Injected\n| a | b |", "docs/a|b `x`.adoc", 0.5,
                "no-test-changes", probs("no-test-changes", 0.5));
        Summary summary = new Summary("Line one.\n# Heading\n| x |", "model|x");

        String report = MarkdownReport.render(c, summary);

        assertTrue(report.startsWith("# PR #57720: Fix | pipes ## Injected | a | b |\n"), report);
        assertTrue(report.contains("- `` docs/a|b `x`.adoc `` (modified)"), report);
        assertTrue(report.contains("Line one. # Heading | x |\n"), report);
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
    void fallbackTemplateListsEveryAnswerInOrder() {
        Summary fallback = FallbackSummary.of(sample(0.88, "no-test-changes", probs("no-test-changes", 0.64)));

        assertEquals("jev classification: behaviour 0.88; tests no-test-changes 0.64.", fallback.text());
        assertEquals("fallback template", fallback.source());
        assertTrue(TerminalRenderer.render(sample(0.88, "no-test-changes", probs("no-test-changes", 0.64)), fallback,
                REPORT).contains("Summary (fallback template): jev classification: behaviour 0.88;"));
    }
}
