package io.quarkus.prreview.review.services;

import io.quarkus.prreview.facts.model.ChangeClassification;
import io.quarkus.prreview.facts.model.CommitterStatus;
import io.quarkus.prreview.facts.model.ExemptClass;
import io.quarkus.prreview.facts.model.Facts;
import io.quarkus.prreview.interpret.model.Band;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.interpret.rules.model.Decisions;
import io.quarkus.prreview.interpret.rules.services.MessageCatalog;
import io.quarkus.prreview.pipelines.model.DeepResult;
import io.quarkus.prreview.pipelines.model.TriageOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link FindingCombiner} (plan §7, §13): the test-adequacy + exempt-class coupling
 * (suppress / downgrade / fail-safe) and the inconsistency-corroborates-breaking-change linkage, asserted with
 * hand-authored pipeline results and pinned facts — no CDI/network.
 */
class FindingCombinerTest {

    private final FindingCombiner combiner = combiner();

    private static FindingCombiner combiner() {
        FindingCombiner c = new FindingCombiner();
        c.messages = new MessageCatalog();
        return c;
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static TriageOutcome triage(Finding... findings) {
        Decisions d = new Decisions(0, List.of(findings), null, null);
        return new TriageOutcome(Map.of(), d, 0, "triage-v1", null, null);
    }

    private static Finding testAdequacyBlocker() {
        return Finding.of(Severity.BLOCKER, "test-adequacy", "test-missing", "no test")
                .withRationale("pipeline:test-adequacy");
    }

    private static DeepResult testAdequacyResult(Finding... findings) {
        return DeepResult.ok("test-adequacy", List.of(findings), Map.of());
    }

    private static DeepResult exemptClassResult(Map<String, BandedAnswer> answers) {
        return DeepResult.silent("exempt-class", answers);
    }

    private static BandedAnswer choice(String id, Band band, String option) {
        return new BandedAnswer(id, band, false, option, Double.NaN, null);
    }

    private static BandedAnswer noulYes(String id) {
        return new BandedAnswer(id, Band.CONFIRM, true, null, Double.NaN, null);
    }

    private static Facts facts(ExemptClass cls) {
        ChangeClassification cc = new ChangeClassification(Map.of("src/main/java/Foo.java", cls));
        return new Facts(null, null, cc, null, CommitterStatus.unknown(), null, false);
    }

    private static Finding only(List<Finding> findings, String concern) {
        return findings.stream().filter(f -> f.concern().equals(concern)).findFirst().orElseThrow();
    }

    // --- test-adequacy + exempt-class ----------------------------------------------------------------

    @Test
    void revertWithConfirmSuppressesTheTestAdequacyBlocker() {
        Map<String, BandedAnswer> exempt = Map.of("exempt_class", choice("exempt_class", Band.CONFIRM, "revert"), "is_genuine_revert", noulYes("is_genuine_revert"));
        List<Finding> out = combiner.combine(triage(),
                Map.of("test-adequacy", testAdequacyResult(testAdequacyBlocker()),
                        "exempt-class", exemptClassResult(exempt)),
                facts(ExemptClass.DELETION_ONLY));
        Finding f = only(out, "test-adequacy");
        assertEquals(Severity.FYI, f.severity(), "a confirmed revert waives the test requirement");
        assertEquals("test-exempt", f.messageKey());
    }

    @Test
    void coveredByExistingDowngradesButNeverSuppresses() {
        Map<String, BandedAnswer> exempt = Map.of("exempt_class", choice("exempt_class", Band.CONFIRM, "covered-by-existing-test"));
        List<Finding> out = combiner.combine(triage(),
                Map.of("test-adequacy", testAdequacyResult(testAdequacyBlocker()),
                        "exempt-class", exemptClassResult(exempt)),
                facts(ExemptClass.COMMENT_ONLY));
        Finding f = only(out, "test-adequacy");
        assertEquals(Severity.NEEDS_ATTENTION, f.severity(), "covered-by-existing never fully clears");
        assertTrue(f.tentative(), "a human must confirm the existing test exercises the change");
    }

    @Test
    void mixedChangeFailSafeIgnoresTheExemptionWhenANonExemptHunkRemains() {
        Map<String, BandedAnswer> exempt = Map.of("exempt_class", choice("exempt_class", Band.CONFIRM, "revert"), "is_genuine_revert", noulYes("is_genuine_revert"));
        // A not-exempt behavioral hunk is present → no exemption may apply whatever exempt-class says (§4.0).
        List<Finding> out = combiner.combine(triage(),
                Map.of("test-adequacy", testAdequacyResult(testAdequacyBlocker()),
                        "exempt-class", exemptClassResult(exempt)),
                facts(ExemptClass.NOT_EXEMPT));
        Finding f = only(out, "test-adequacy");
        assertEquals(Severity.BLOCKER, f.severity(), "the deterministic fail-safe keeps the Blocker");
    }

    @Test
    void subConfirmClassLeavesTheTestAdequacyBlockerStanding() {
        Map<String, BandedAnswer> exempt = Map.of("exempt_class", choice("exempt_class", Band.FLAG, "revert"), "is_genuine_revert", noulYes("is_genuine_revert"));
        List<Finding> out = combiner.combine(triage(),
                Map.of("test-adequacy", testAdequacyResult(testAdequacyBlocker()),
                        "exempt-class", exemptClassResult(exempt)),
                facts(ExemptClass.DELETION_ONLY));
        Finding f = only(out, "test-adequacy");
        assertEquals(Severity.BLOCKER, f.severity(),
                "an unconfirmed exempt class does not suppress the test-adequacy finding");
    }

    // --- inconsistency corroborates breaking-change --------------------------------------------------

    @Test
    void falseNotBreakingAnnotatesTheBreakingChangeButNeverCreatesIt() {
        Finding breakingFinding = Finding.of(Severity.BLOCKER, "breaking-change", "breaking-change-no-migration", "break")
                .withRationale("pipeline:breaking-change");
        DeepResult breaking = DeepResult.ok("breaking-change", List.of(breakingFinding), Map.of());
        DeepResult inconsistency = DeepResult.ok("inconsistency", List.of(),
                Map.of("mismatch_kind", choice("mismatch_kind", Band.CONFIRM, "false-not-breaking-claim")));

        List<Finding> out = combiner.combine(triage(),
                Map.of("breaking-change", breaking, "inconsistency", inconsistency),
                facts(ExemptClass.NOT_EXEMPT));
        Finding f = only(out, "breaking-change");
        assertTrue(f.rationale().contains("PR text claims non-breaking"),
                "inconsistency corroborates the breaking-change rationale");
    }

    @Test
    void inconsistencyNeverCreatesABreakingChangeThatDidNotFire() {
        DeepResult inconsistency = DeepResult.ok("inconsistency", List.of(),
                Map.of("mismatch_kind", choice("mismatch_kind", Band.CONFIRM, "false-not-breaking-claim")));
        List<Finding> out = combiner.combine(triage(), Map.of("inconsistency", inconsistency),
                facts(ExemptClass.NOT_EXEMPT));
        assertFalse(out.stream().anyMatch(f -> f.concern().equals("breaking-change")),
                "inconsistency never feeds breaking-change");
    }

    // --- dedup -------------------------------------------------------------------------------------

    @Test
    void mostSeverePerConcernKeepsTheBlocker() {
        Finding fyi = Finding.of(Severity.FYI, "test-adequacy", "x", "fyi");
        Finding blocker = Finding.of(Severity.BLOCKER, "test-adequacy", "y", "blocker");
        List<Finding> out = FindingCombiner.mostSeverePerConcern(List.of(fyi, blocker));
        assertEquals(1, out.size());
        assertEquals(Severity.BLOCKER, out.get(0).severity());
    }
}
