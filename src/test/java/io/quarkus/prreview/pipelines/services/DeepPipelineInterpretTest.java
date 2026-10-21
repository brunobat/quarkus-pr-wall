package io.quarkus.prreview.pipelines.services;

import io.quarkus.prreview.facts.model.CommitterStatus;
import io.quarkus.prreview.facts.model.Facts;
import io.quarkus.prreview.interpret.model.Band;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.interpret.rules.services.MessageCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the deep-pipeline {@code interpret} functions (plan §7, §13). Each interpret is a
 * function over hand-authored banded answers + pinned facts, so the §4/§6 decision rules are asserted with
 * no network or CDI — only {@link DeepSupport} wired to a real {@link MessageCatalog} for summaries.
 */
class DeepPipelineInterpretTest {

    private static final DeepSupport SUPPORT = support();

    private static DeepSupport support() {
        DeepSupport s = new DeepSupport();
        s.messages = new MessageCatalog();
        return s;
    }

    // --- banded-answer fixtures --------------------------------------------------------------------

    private static BandedAnswer yes(String id) {
        return new BandedAnswer(id, Band.CONFIRM, true, null, Double.NaN, null);
    }

    private static BandedAnswer no(String id) {
        return new BandedAnswer(id, Band.CONFIRM, false, null, Double.NaN, null);
    }

    private static BandedAnswer flagNo(String id) {
        return new BandedAnswer(id, Band.FLAG, false, null, Double.NaN, null);
    }

    private static BandedAnswer flagYes(String id) {
        return new BandedAnswer(id, Band.FLAG, true, null, Double.NaN, null);
    }

    private static BandedAnswer choice(String id, String option) {
        return new BandedAnswer(id, Band.CONFIRM, false, option, Double.NaN, null);
    }

    private static BandedAnswer score(String id, double value) {
        return new BandedAnswer(id, Band.CONFIRM, false, null, value, null);
    }

    private static Facts facts(CommitterStatus.Level level, boolean backport) {
        return new Facts(null, null, null, null, new CommitterStatus(level, "sha"), null, backport);
    }

    private static Facts committer() {
        return facts(CommitterStatus.Level.COMMITTER, false);
    }

    private static Facts nonCommitter() {
        return facts(CommitterStatus.Level.CONTRIBUTOR, false);
    }

    private static Finding one(List<Finding> findings) {
        assertEquals(1, findings.size(), "expected exactly one finding");
        return findings.get(0);
    }

    // --- A: test-adequacy --------------------------------------------------------------------------

    @Test
    void aVacuousAssertionsAreZeroTests() {
        TestAdequacyPipeline a = new TestAdequacyPipeline();
        a.support = SUPPORT;
        Finding f = one(a.interpret(Map.of("all_tests_vacuous", yes("all_tests_vacuous")), nonCommitter()));
        assertEquals(Severity.BLOCKER, f.severity());
        assertEquals("test-vacuous", f.messageKey());

        Finding fc = one(a.interpret(Map.of("all_tests_vacuous", yes("all_tests_vacuous")), committer()));
        assertEquals(Severity.NEEDS_ATTENTION, fc.severity(), "committer asymmetry downgrades the hard rule");
    }

    @Test
    void aAdequateConfirmIsSilent() {
        TestAdequacyPipeline a = new TestAdequacyPipeline();
        a.support = SUPPORT;
        Map<String, BandedAnswer> clean = Map.of(
                "test_exercises_changed_path", yes("test_exercises_changed_path"), "accompanying_test_asserts_behavior", yes("accompanying_test_asserts_behavior"), "all_tests_vacuous", no("all_tests_vacuous"), "case_coverage", score("case_coverage", 2));
        assertTrue(a.interpret(clean, committer()).isEmpty(), "adequate + CONFIRM clears to silence");
    }

    @Test
    void aWeakCoverageIsNeedsAttention() {
        TestAdequacyPipeline a = new TestAdequacyPipeline();
        a.support = SUPPORT;
        Finding f = one(a.interpret(Map.of(
                "test_exercises_changed_path", yes("test_exercises_changed_path"), "accompanying_test_asserts_behavior", yes("accompanying_test_asserts_behavior"), "all_tests_vacuous", no("all_tests_vacuous"), "case_coverage", score("case_coverage", 1)), committer()));
        assertEquals(Severity.NEEDS_ATTENTION, f.severity());
        assertEquals("test-adequacy-weak", f.messageKey());
    }

    // --- C: breaking-change ------------------------------------------------------------------------

    @Test
    void cBackportBreakIsBlocker() {
        BreakingChangePipeline c = new BreakingChangePipeline();
        c.support = SUPPORT;
        Finding f = one(c.interpret(Map.of("diff_breaks_callers", yes("diff_breaks_callers")), facts(CommitterStatus.Level.COMMITTER, true)));
        assertEquals(Severity.BLOCKER, f.severity());
        assertEquals("breaking-change-backported", f.messageKey());
    }

    @Test
    void cBreakWithoutMigrationIsBlocker() {
        BreakingChangePipeline c = new BreakingChangePipeline();
        c.support = SUPPORT;
        Finding f = one(c.interpret(Map.of("diff_breaks_callers", yes("diff_breaks_callers"), "diff_has_migration_note", no("diff_has_migration_note")), committer()));
        assertEquals(Severity.BLOCKER, f.severity());
        assertEquals("breaking-change-no-migration", f.messageKey());
    }

    @Test
    void cDocumentedBreakOnMainIsSilent() {
        BreakingChangePipeline c = new BreakingChangePipeline();
        c.support = SUPPORT;
        assertTrue(c.interpret(Map.of("diff_breaks_callers", yes("diff_breaks_callers"), "diff_has_migration_note", yes("diff_has_migration_note")), committer()).isEmpty());
    }

    @Test
    void cPossibleBreakIsTentative() {
        BreakingChangePipeline c = new BreakingChangePipeline();
        c.support = SUPPORT;
        Finding f = one(c.interpret(Map.of("diff_breaks_callers", flagNo("diff_breaks_callers")), committer()));
        assertEquals(Severity.NEEDS_ATTENTION, f.severity());
        assertTrue(f.tentative());
    }

    @Test
    void cNoBreakIsSilent() {
        BreakingChangePipeline c = new BreakingChangePipeline();
        c.support = SUPPORT;
        assertTrue(c.interpret(Map.of("diff_breaks_callers", no("diff_breaks_callers")), committer()).isEmpty());
    }

    // --- D: test-weakening -------------------------------------------------------------------------

    @Test
    void dWeakeningWithBehaviorChangeIsBlocker() {
        TestWeakeningPipeline d = new TestWeakeningPipeline();
        d.support = SUPPORT;
        Finding f = one(d.interpret(Map.of("weakens_existing_coverage", yes("weakens_existing_coverage"), "coincides_with_runtime_change", yes("coincides_with_runtime_change"))));
        assertEquals(Severity.BLOCKER, f.severity());
        assertEquals("tests-weakened", f.messageKey());
    }

    @Test
    void dRefactorWeakeningIsNeedsAttention() {
        TestWeakeningPipeline d = new TestWeakeningPipeline();
        d.support = SUPPORT;
        Finding f = one(d.interpret(Map.of("weakens_existing_coverage", yes("weakens_existing_coverage"), "coincides_with_runtime_change", no("coincides_with_runtime_change"))));
        assertEquals(Severity.NEEDS_ATTENTION, f.severity());
        assertEquals("tests-weakened-refactor", f.messageKey());
    }

    @Test
    void dSubConfirmIsTentative() {
        TestWeakeningPipeline d = new TestWeakeningPipeline();
        d.support = SUPPORT;
        Finding f = one(d.interpret(Map.of("weakens_existing_coverage", flagNo("weakens_existing_coverage"), "coincides_with_runtime_change", yes("coincides_with_runtime_change"))));
        assertEquals(Severity.NEEDS_ATTENTION, f.severity());
        assertTrue(f.tentative());
    }

    // --- E: docs-missing ---------------------------------------------------------------------------

    @Test
    void eNewConfigWithoutDocsIsNeedsAttention() {
        DocsMissingPipeline e = new DocsMissingPipeline();
        e.support = SUPPORT;
        Finding f = one(e.interpret(Map.of("introduces_documentable_surface", yes("introduces_documentable_surface"), "has_docs", no("has_docs"))));
        assertEquals(Severity.NEEDS_ATTENTION, f.severity());
        assertFalse(f.tentative());
    }

    @Test
    void eDocsPresentIsSilent() {
        DocsMissingPipeline e = new DocsMissingPipeline();
        e.support = SUPPORT;
        assertTrue(e.interpret(Map.of("introduces_documentable_surface", yes("introduces_documentable_surface"), "has_docs", yes("has_docs"))).isEmpty());
    }

    @Test
    void eNothingNewIsSilent() {
        DocsMissingPipeline e = new DocsMissingPipeline();
        e.support = SUPPORT;
        assertTrue(e.interpret(Map.of("introduces_documentable_surface", no("introduces_documentable_surface"))).isEmpty());
    }

    // --- F: domain routing -------------------------------------------------------------------------

    @Test
    void fRoutesEachFlaggedDomain() {
        DomainRoutingPipeline f = new DomainRoutingPipeline();
        f.support = SUPPORT;
        List<Finding> findings = f.interpret(Map.of("touches_security", yes("touches_security"), "touches_messaging", flagYes("touches_messaging"),
                "touches_persistence", no("touches_persistence"), "touches_native_image", flagNo("touches_native_image")));
        assertEquals(2, findings.size(), "security (CONFIRM) + messaging (FLAG) route; persistence/native do not");
        assertTrue(findings.stream().allMatch(x -> x.severity() == Severity.NEEDS_ATTENTION));
    }

    // --- G: inconsistency --------------------------------------------------------------------------

    @Test
    void gAccurateDescriptionIsSilent() {
        InconsistencyPipeline g = new InconsistencyPipeline();
        g.support = SUPPORT;
        assertTrue(g.interpret(Map.of("text_matches_diff", yes("text_matches_diff"))).isEmpty());
    }

    @Test
    void gUndisclosedChangeIsNeedsAttention() {
        InconsistencyPipeline g = new InconsistencyPipeline();
        g.support = SUPPORT;
        Finding f = one(g.interpret(Map.of("text_matches_diff", no("text_matches_diff"), "mismatch_kind", choice("mismatch_kind", "undisclosed-change"))));
        assertEquals(Severity.NEEDS_ATTENTION, f.severity());
        assertEquals("inconsistency-undisclosed", f.messageKey());
        assertFalse(f.tentative(), "CONFIRM-false is a firm inconsistency");
    }

    @Test
    void gShakyMismatchIsTentative() {
        InconsistencyPipeline g = new InconsistencyPipeline();
        g.support = SUPPORT;
        Finding f = one(g.interpret(Map.of("text_matches_diff", flagNo("text_matches_diff"))));
        assertEquals("inconsistency", f.messageKey());
        assertTrue(f.tentative());
    }
}
