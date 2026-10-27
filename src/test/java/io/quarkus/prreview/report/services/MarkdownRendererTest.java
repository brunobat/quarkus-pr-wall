package io.quarkus.prreview.report.services;

import io.quarkus.prreview.interpret.model.Band;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.review.model.AuthorContext;
import io.quarkus.prreview.review.model.PipelineRun;
import io.quarkus.prreview.review.model.Provenance;
import io.quarkus.prreview.review.model.RangeReport;
import io.quarkus.prreview.review.model.ReviewResult;
import io.quarkus.prreview.jev.model.Answer;
import io.quarkus.prreview.pipelines.model.DeepStatus;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return reviewed(findings, Map.of());
    }

    private static ReviewResult reviewed(List<Finding> findings, Map<String, BandedAnswer> answers) {
        return new ReviewResult(42, "Fix the thing", 2, 3, findings,
                List.of(new PipelineRun("test-adequacy", DeepStatus.OK, null)),
                answers, prov(), null, ReviewResult.Status.REVIEWED, null);
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

    // --- triage decisions --------------------------------------------------------------------------

    private static BandedAnswer noul(String id, double pYes, Band band) {
        return new BandedAnswer(id, band, pYes >= 0.5, null, Double.NaN,
                new Answer("noul", null, null, pYes, null, null, null));
    }

    private static BandedAnswer choice(String id, String picked, double p, Band band) {
        return new BandedAnswer(id, band, false, picked, Double.NaN,
                new Answer("choice", picked, null, null, Map.of(picked, p), p, null));
    }

    private static BandedAnswer score(String id, double value, double confidence, Band band) {
        return new BandedAnswer(id, band, false, null, value,
                new Answer("score", null, value, null, null, confidence, null));
    }

    /** Insertion order, because the sweep's columns follow it and a reshuffled report is unreadable. */
    private static Map<String, BandedAnswer> answers(BandedAnswer... as) {
        Map<String, BandedAnswer> m = new LinkedHashMap<>();
        for (BandedAnswer a : as) {
            m.put(a.id(), a);
        }
        return m;
    }

    @Test
    void eachDecisionIsReportedWithTheProbabilityBehindIt() {
        String md = renderer.renderPr(reviewed(List.of(), answers(
                choice("change_kind", "bugfix", 0.82, Band.CONFIRM),
                score("blast_radius", 3.4, 0.71, Band.CONFIRM),
                noul("breaks_callers", 0.97, Band.CONFIRM))));

        assertTrue(md.contains("## Triage decisions"), md);
        assertTrue(md.contains("| change_kind | choice | bugfix | 0.82 | CONFIRM |"), md);
        assertTrue(md.contains("| blast_radius | score | 3.4 | 0.71 | CONFIRM |"), md);
        assertTrue(md.contains("| breaks_callers | noul | yes | 0.97 | CONFIRM |"), md);
    }

    /**
     * The one place the two question types disagree about what {@code p} means. A noul answer returns
     * p(yes), so a {@code no} must report the complement — printing 0.03 beside "no" would read as the
     * model being 3% sure of the answer it gave.
     */
    @Test
    void aNoAnswerReportsTheProbabilityOfTheAnswerNotOfYes() {
        String md = renderer.renderPr(reviewed(List.of(), answers(
                noul("breaks_callers", 0.03, Band.CONFIRM))));

        assertTrue(md.contains("| breaks_callers | noul | no | 0.97 | CONFIRM |"), md);
        assertTrue(md.contains("1 − p(yes)"), "the legend has to say so: " + md);
    }

    @Test
    void aPrWithNoAnswersOmitsTheDecisionTableRatherThanRenderingAnEmptyOne() {
        String md = renderer.renderPr(reviewed(List.of()));

        assertFalse(md.contains("## Triage decisions"), md);
    }

    @Test
    void theSweepLaysDecisionsOutAsOneColumnPerQuestion() {
        ReviewResult a = withAnswers(1, answers(
                choice("change_kind", "bugfix", 0.82, Band.CONFIRM),
                noul("breaks_callers", 0.97, Band.CONFIRM)));
        ReviewResult b = withAnswers(2, answers(
                choice("change_kind", "feature", 0.55, Band.FLAG),
                noul("breaks_callers", 0.40, Band.HUMAN)));

        String md = renderer.renderRange(new RangeReport(List.of(a, b), List.of()));

        assertTrue(md.contains("| PR | change_kind | breaks_callers |"), md);
        assertTrue(md.contains("| #1 | bugfix 0.82 | yes 0.97 |"), md);
        assertTrue(md.contains("| #2 | feature 0.55~ | no 0.60? |"), md);
    }

    /**
     * A PR answered against a different question set must not shift its neighbours' columns — the
     * comparison down a column is the whole point of the matrix.
     */
    @Test
    void aQuestionMissingFromOnePrLeavesAnEmptyCellNotAShiftedRow() {
        ReviewResult complete = withAnswers(1, answers(
                choice("change_kind", "bugfix", 0.82, Band.CONFIRM),
                noul("breaks_callers", 0.97, Band.CONFIRM)));
        ReviewResult partial = withAnswers(2, answers(
                noul("breaks_callers", 0.97, Band.CONFIRM)));

        String md = renderer.renderRange(new RangeReport(List.of(complete, partial), List.of()));

        assertTrue(md.contains("| #2 |  | yes 0.97 |"), md);
    }

    @Test
    void anErroredSweepRendersNoDecisionTable() {
        String md = renderer.renderRange(new RangeReport(List.of(), List.of(ReviewResult.errored(9, "boom"))));

        assertFalse(md.contains("## Triage decisions"), md);
    }

    private static ReviewResult withAnswers(int pr, Map<String, BandedAnswer> answers) {
        return new ReviewResult(pr, "t", 2, 3, List.of(), List.of(), answers, prov(), null,
                ReviewResult.Status.REVIEWED, null);
    }

    // --- author context (authorship-provenance design §4.6) ----------------------------------------

    private static ReviewResult withAuthor(AuthorContext a) {
        return new ReviewResult(42, "Fix the thing", 2, 3, List.of(),
                List.of(new PipelineRun("test-adequacy", DeepStatus.OK, null)),
                Map.of(), prov(), a, ReviewResult.Status.REVIEWED, null);
    }

    @Test
    void aDeclaredAssistanceIsStatedPlainlyAndWithoutATool() {
        String md = renderer.renderPr(withAuthor(
                new AuthorContext("alice", true, false, false, true, true)));

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
                new AuthorContext("alice", false, false, false, false, true)));

        assertFalse(md.contains("**Author:**"), md);
        assertFalse(md.toLowerCase(java.util.Locale.ROOT).contains("ai assistance"), md);
        assertFalse(md.toLowerCase(java.util.Locale.ROOT).contains("declar"), md);
    }

    @Test
    void anAllowlistedBotIsLabelledRoutineRatherThanJustBot() {
        String md = renderer.renderPr(withAuthor(
                new AuthorContext("dependabot", false, true, true, false, false)));

        assertTrue(md.contains("**Author:** dependabot (routine automation)"), md);
    }

    @Test
    void aNonAllowlistedBotIsLabelledAsAnAccountTypeOnly() {
        String md = renderer.renderPr(withAuthor(
                new AuthorContext("copilot-swe-agent", false, true, false, false, false)));

        assertTrue(md.contains("**Author:** copilot-swe-agent (bot account)"), md);
    }

    @Test
    void aMissingAuthorContextRendersNothingRatherThanFailing() {
        String md = renderer.renderPr(withAuthor(null));

        assertFalse(md.contains("**Author:**"));
        assertTrue(md.contains("## Findings"));
    }

    /**
     * A bot opened it but a person wrote the commits — the one case where the account type is
     * misleading on its own, and the only reason {@code hasHumanCommitAuthor} is carried this far.
     */
    @Test
    void humanCommitsUnderABotAccountAreStated() {
        String md = renderer.renderPr(withAuthor(
                new AuthorContext("dependabot", false, true, true, false, true)));

        assertTrue(md.contains("**Author:** dependabot (routine automation)  ·  carries human-authored "
                + "commits"), authorLine(md));
    }

    /** The same fact on a human account says nothing, so it is not printed. */
    @Test
    void humanCommitsUnderAHumanAccountAreNotWorthStating() {
        String md = renderer.renderPr(withAuthor(
                new AuthorContext("alice", true, false, false, false, true)));

        assertEquals("**Author:** alice (committer)", authorLine(md));
    }

    // --- the sweep's Author column -----------------------------------------------------------------

    private static ReviewResult ranked(int pr, AuthorContext a) {
        return new ReviewResult(pr, "Bump something", 2, 3, List.of(), List.of(), Map.of(), prov(), a,
                ReviewResult.Status.REVIEWED, null);
    }

    /** The four buckets a reviewer scans the sweep for, each from a different attested fact. */
    @Test
    void theSweepLabelsEachAuthorKindFromTheAttestedFacts() {
        String md = renderer.renderRange(new RangeReport(List.of(
                ranked(1, new AuthorContext("dependabot", false, true, true, false, false)),
                ranked(2, new AuthorContext("copilot-swe-agent", false, true, false, false, false)),
                ranked(3, new AuthorContext("alice", false, false, false, true, true)),
                ranked(4, new AuthorContext("bob", false, false, false, false, true))),
                List.of()));

        assertTrue(md.contains("| dependabot (bot) |"), md);
        assertTrue(md.contains("| copilot-swe-agent (agent) |"), md);
        assertTrue(md.contains("| alice (human+ai) |"), md);
        assertTrue(md.contains("| bob (human) |"), md);
    }

    /**
     * The legend is not decoration. {@code human} is the bucket a PR lands in when nothing was
     * declared, which is indistinguishable from an undeclared AI-assisted PR — a column that shows the
     * label without the caveat is an AI detector as far as the reader is concerned, and this agent
     * does not have one.
     */
    @Test
    void theAuthorColumnShipsWithItsCaveat() {
        String md = renderer.renderRange(new RangeReport(List.of(
                ranked(1, new AuthorContext("bob", false, false, false, false, true))), List.of()));

        assertTrue(md.contains("`human` means no declaration, not no AI"), md);
    }

    @Test
    void aBotOpenedPrCarryingHumanCommitsIsMarkedInTheSweepToo() {
        String md = renderer.renderRange(new RangeReport(List.of(
                ranked(1, new AuthorContext("dependabot", false, true, true, false, true))), List.of()));

        assertTrue(md.contains("| dependabot (bot +commits) |"), md);
    }

    /** An errored PR has no facts, so the cell says so rather than defaulting to the human residual. */
    @Test
    void anUnknownAuthorIsNotQuietlyCalledHuman() {
        String md = renderer.renderRange(new RangeReport(List.of(ranked(1, null)), List.of()));

        assertTrue(md.contains("| — |"), md);
        assertFalse(md.contains("(human)"), md);
    }
}
