package io.quarkus.prreview.report.services;

import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.review.model.PipelineRun;
import io.quarkus.prreview.review.model.Provenance;
import io.quarkus.prreview.review.model.RangeReport;
import io.quarkus.prreview.review.model.ReviewResult;
import io.quarkus.prreview.pipelines.model.DeepStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                prov(), ReviewResult.Status.REVIEWED, null);
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
}
