package io.quarkus.prreview.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.github.model.Approval;
import io.quarkus.prreview.classification.github.model.CiChecks;
import io.quarkus.prreview.classification.model.SourceResult;
import io.quarkus.prreview.triage.model.PrOutcome;
import io.quarkus.prreview.triage.model.PullRequest;
import io.quarkus.prreview.triage.model.TestOutcomes;

/** The escaping helpers and the report header on their own; whole reports are checked in {@code RenderingTest}. */
class MarkdownReportTest {

    @Test
    void cellEscapesPipesAndTheBackslashesBeforeThem() {
        assertEquals("a \\| b", MarkdownReport.cell("a | b"));
        // a\|b: the backslash is escaped too, otherwise "\\|" would read as an escaped backslash and a bare pipe.
        assertEquals("a\\\\\\|b", MarkdownReport.cell("a\\|b"));
        assertEquals("a\\\\\\\\\\|b", MarkdownReport.cell("a\\\\|b"));
        // Backslashes not before a pipe are left alone.
        assertEquals("C:\\dir", MarkdownReport.cell("C:\\dir"));
        assertEquals("one line", MarkdownReport.cell("one\n line"));
    }

    @Test
    void inlineTextCannotOpenHtml() {
        assertEquals("&lt;!-- hidden", MarkdownReport.inline("<!-- hidden"));
        assertEquals("&lt;img src=x> &amp;lt; a > b", MarkdownReport.inline("<img src=x> &lt; a > b"));
        assertEquals("&lt;b> \\| x", MarkdownReport.cell("<b> | x"));
        // Code spans are shown literally by GitHub, so they keep the text as is.
        assertEquals("`<!--`", MarkdownReport.code("<!--"));
    }

    @Test
    void codeSpansInsideTextAreLeftAsTyped() {
        assertEquals("Fix `Optional<T>` &amp; `a&b` &lt;b>", MarkdownReport.inline("Fix `Optional<T>` & `a&b` <b>"));
        assertEquals("``x ` <y`` &lt;z", MarkdownReport.inline("``x ` <y`` <z"));
        // An unmatched backtick is literal, so what follows it is still escaped.
        assertEquals("a ` &lt;b", MarkdownReport.inline("a ` <b"));
        assertEquals("`` &lt;b ` c", MarkdownReport.inline("`` <b ` c"));
        // An escaped backtick opens no span; an escaped backslash leaves the backtick after it live.
        assertEquals("\\` &lt;b` c", MarkdownReport.inline("\\` <b` c"));
        assertEquals("\\\\`<b>`", MarkdownReport.inline("\\\\`<b>`"));
        // A backslash before & or < is replaced by the entity, which shows the same character.
        assertEquals("&lt;b> &amp;", MarkdownReport.inline("\\<b> \\&"));
        // Table cells escape pipes inside code spans too.
        assertEquals("`a\\|b` &lt;c", MarkdownReport.cell("`a|b` <c"));
    }

    @Test
    void codeUsesALongerFenceAroundBackticks() {
        assertEquals("`a.java`", MarkdownReport.code("a.java"));
        assertEquals("`` a`b ``", MarkdownReport.code("a`b"));
        // A run of two backticks in the text needs a fence of three, or the text would close the span.
        assertEquals("``` a``<details>b ```", MarkdownReport.code("a``<details>b"));
        assertEquals("```` ```x ````", MarkdownReport.code("```x"));
    }

    static final String SHA = "ba9690af0e8bdc5d1c0f7a6c2b6b1e7d3c9a4f21";
    static final PullRequest PR = new PullRequest(7, "Title", "https://github.com/q/q/pull/7", "OPEN", true, "");

    @Test
    void theLinkLineEndsWithTheCommitCiWasReadFor() {
        String report = MarkdownReport.render(outcome(PR, new SourceResult.Available<>(
                new CiChecks(SHA, List.of(), List.of(), Approval.NOT_WAITING, List.of()))));

        assertTrue(report.startsWith("# PR #7: Title\n\nLink: https://github.com/q/q/pull/7 · State: OPEN (draft)"
                + " · CI on commit ba9690a\n\n## Size (GitHub)\n"), report);
    }

    @Test
    void withoutCiTheCommitIsNotShown() {
        String report = MarkdownReport.render(outcome(PR, SourceResult.unavailable()));

        assertTrue(report.startsWith("# PR #7: Title\n\nLink: https://github.com/q/q/pull/7 · State: OPEN (draft)\n\n"
                + "## Size (GitHub)\n"), report);
        assertFalse(report.contains("CI on commit"), report);
    }

    @Test
    void withoutALinkLineTheCommitHasALineOfItsOwn() {
        String report = MarkdownReport.render(outcome(null, new SourceResult.Available<>(
                new CiChecks(SHA, List.of(), List.of(), Approval.NOT_WAITING, List.of()))));

        assertTrue(report.startsWith("# PR #7\n\nCI on commit ba9690a\n\n## Size (GitHub)\n"), report);
    }

    private static PrOutcome outcome(PullRequest pr, SourceResult<CiChecks> ci) {
        PrOutcome outcome = TestOutcomes.notAnswered(7, List.of("test-adequacy"), pr, "gh: x", "x", null, null, null,
                null);
        return new PrOutcome(7, outcome.checks(), pr, outcome.jev(), outcome.size(), ci, outcome.provenance());
    }
}
