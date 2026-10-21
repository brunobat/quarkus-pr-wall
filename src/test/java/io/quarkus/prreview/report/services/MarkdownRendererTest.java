package io.quarkus.prreview.report.services;

import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.review.model.AuthorContext;
import io.quarkus.prreview.review.model.PipelineRun;
import io.quarkus.prreview.review.model.Provenance;
import io.quarkus.prreview.review.model.RangeReport;
import io.quarkus.prreview.review.model.ReviewResult;
import io.quarkus.prreview.pipelines.model.DeepStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shape tests for {@link MarkdownRenderer} — pure templating over structured results (plan §9). */
class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    private static Provenance prov() {
        return new Provenance("abc123", "jev-1", "claude-opus-5", "triage-v1", "rs-1", "th-1");
    }

    private static ReviewResult reviewed(List<Finding> findings) {
        return new ReviewResult(42, "Fix the thing", 2, 3, findings,
                List.of(new PipelineRun("test-adequacy", DeepStatus.OK, null)),
                prov(), null, ReviewResult.Status.REVIEWED, null);
    }

    @Test
    void emptyFindingsRenderSilenceByDefault() {
        String md = renderer.renderPr(reviewed(List.of()));
        assertTrue(md.contains("silence by default"), "a clean PR states silence, not an empty report");
        assertTrue(md.contains("# PR #42"));
    }

    @Test
    void blockerFindingRendersSeverityLocationAndFix() {
        Finding f = Finding.of(Severity.BLOCKER, "test-adequacy", "test-missing", "no test")
                .at("src/Foo.java", 12)
                .withFixProse("Add a unit test exercising the changed branch.");
        String md = renderer.renderPr(reviewed(List.of(f)));
        assertTrue(md.contains("[BLOCKER]"));
        assertTrue(md.contains("src/Foo.java:12"));
        assertTrue(md.contains("_fix:_ Add a unit test"));
    }

    @Test
    void tentativeFindingIsMarkedHumanConfirm() {
        Finding f = Finding.of(Severity.NEEDS_ATTENTION, "docs-missing", "docs-missing", "no docs").tentative(true);
        String md = renderer.renderPr(reviewed(List.of(f)));
        assertTrue(md.contains("(human confirm)"));
    }

    @Test
    void erroredPrReportsTheFailureNotFindings() {
        String md = renderer.renderPr(ReviewResult.errored(7, "gh timed out"));
        assertTrue(md.contains("Could not be reviewed automatically"));
        assertTrue(md.contains("gh timed out"));
        assertFalse(md.contains("## Findings"));
    }

    @Test
    void rangeRendersRankedAndErroredSections() {
        ReviewResult ok = reviewed(List.of(
                Finding.of(Severity.BLOCKER, "breaking-change", "breaking-change-no-migration", "breaks callers")));
        ReviewResult err = ReviewResult.errored(99, "not found");
        String md = renderer.renderRange(new RangeReport(List.of(ok), List.of(err)));
        assertTrue(md.contains("## Ranked"));
        assertTrue(md.contains("#42"));
        assertTrue(md.contains("Could not be reviewed automatically"));
        assertTrue(md.contains("#99"));
    }

    // --- author context (authorship-provenance design §4.6) ----------------------------------------

    private static ReviewResult withAuthor(AuthorContext a) {
        return new ReviewResult(42, "Fix the thing", 2, 3, List.of(),
                List.of(new PipelineRun("test-adequacy", DeepStatus.OK, null)),
                prov(), a, ReviewResult.Status.REVIEWED, null);
    }

    @Test
    void aDeclaredAssistanceIsStatedPlainlyAndWithoutATool() {
        String md = renderer.renderPr(withAuthor(
                new AuthorContext("alice", true, false, false, true)));

        assertEquals("**Author:** alice (committer)  ·  AI assistance declared", authorLine(md),
                "the line states that assistance was declared and nothing else — no tool, no judgment");
    }

    /** The one rendered author line, so an assertion about it cannot be satisfied by other sections. */
    private static String authorLine(String md) {
        return md.lines().filter(l -> l.startsWith("**Author:**")).findFirst().orElse("");
    }

    /**
     * The asymmetry that matters most in the whole feature. Almost every AI-assisted PR carries no
     * marker, so printing anything in the no-declaration case would be asserting a clean bill of
     * health the agent cannot issue. The line simply does not appear.
     */
    @Test
    void anUndeclaredPrSaysNothingAboutTooling() {
        String md = renderer.renderPr(withAuthor(
                new AuthorContext("alice", false, false, false, false)));

        assertFalse(md.contains("**Author:**"), md);
        assertFalse(md.toLowerCase(java.util.Locale.ROOT).contains("ai assistance"), md);
        assertFalse(md.toLowerCase(java.util.Locale.ROOT).contains("declar"), md);
    }

    @Test
    void anAllowlistedBotIsLabelledRoutineRatherThanJustBot() {
        String md = renderer.renderPr(withAuthor(
                new AuthorContext("dependabot", false, true, true, false)));

        assertTrue(md.contains("**Author:** dependabot (routine automation)"), md);
    }

    @Test
    void aNonAllowlistedBotIsLabelledAsAnAccountTypeOnly() {
        String md = renderer.renderPr(withAuthor(
                new AuthorContext("copilot-swe-agent", false, true, false, false)));

        assertTrue(md.contains("**Author:** copilot-swe-agent (bot account)"), md);
    }

    @Test
    void aMissingAuthorContextRendersNothingRatherThanFailing() {
        String md = renderer.renderPr(withAuthor(null));

        assertFalse(md.contains("**Author:**"));
        assertTrue(md.contains("## Findings"));
    }
}
