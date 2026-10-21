package io.quarkus.prreview.interpret.rules.services;

import io.quarkus.prreview.interpret.rules.model.Decisions;
import io.quarkus.prreview.interpret.rules.model.Rule;
import io.quarkus.prreview.interpret.rules.model.RuleEffect;
import io.quarkus.prreview.interpret.rules.model.RulePredicate;
import io.quarkus.prreview.interpret.rules.model.Ruleset;
import io.quarkus.prreview.interpret.model.Band;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.services.FactView;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.interpret.model.Thresholds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link RuleEngine} (plan §13): the engine is a function over hand-authored answer +
 * fact fixtures, so each guarantee is asserted without any network or CDI. Includes the <em>confluence</em>
 * test (order-independence for findings/priority) and a <em>hostile-ruleset</em> meta-test (an edit that
 * tries to lower priority or silence on uncertainty is rejected or inert).
 */
class RuleEngineTest {

    private static final Thresholds T = Thresholds.defaults();
    private static final MessageCatalog MESSAGES = new MessageCatalog();

    // --- fixture helpers ---------------------------------------------------------------------------

    private static BandedAnswer confirmNo(String id) {
        return new BandedAnswer(id, Band.CONFIRM, false, null, Double.NaN, null);
    }

    private static BandedAnswer confirmChoice(String id, String choice) {
        return new BandedAnswer(id, Band.CONFIRM, false, choice, Double.NaN, null);
    }

    private static BandedAnswer flagChoice(String id, String choice) {
        return new BandedAnswer(id, Band.FLAG, false, choice, Double.NaN, null);
    }

    private static RulePredicate answer(String id, Band band, String is, String choice) {
        return new RulePredicate(id, band, is, choice, null, null, null, null);
    }

    private static RulePredicate fact(String name, String equals) {
        return new RulePredicate(null, null, null, null, null, null, name, equals);
    }

    private static Rule emit(String id, String concern, Severity sev, List<RulePredicate> when) {
        return new Rule(id, concern, null, when, RuleEffect.emit, sev, "msg-" + id, null, null, null, null, null,
                true);
    }

    private static Rule raise(String id, int value, List<RulePredicate> when) {
        return new Rule(id, "review-priority", null, when, RuleEffect.raisePriority, null, null, null, value, null,
                null, null, true);
    }

    private static Rule silence(String id, String concern, List<RulePredicate> when) {
        return new Rule(id, concern, null, when, RuleEffect.silence, null, null, null, null, null, null, null,
                true);
    }

    private static Rule escalate(String id, String pipeline, List<RulePredicate> when) {
        return new Rule(id, "c-" + pipeline, null, when, RuleEffect.escalate, null, null, pipeline, null, null,
                null, null, true);
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    void securityFloorRaisesPriorityRegardlessOfBase() {
        FactView facts = new FactView().put("has_security_file", true);
        Decisions d = RuleEngine.evaluate(Map.of(), facts, new Ruleset("t", List.of()), T, 0, MESSAGES);
        assertEquals(2, d.priority(), "I-1 floors security-sensitive paths to read-closely");
    }

    @Test
    void mostSeverePerConcernWins() {
        Map<String, BandedAnswer> answers = Map.of("test_situation", confirmChoice("test_situation", "no-tests"));
        Ruleset rs = new Ruleset("t", List.of(
                emit("a-fyi", "test-adequacy", Severity.FYI, List.of(answer("test_situation", Band.CONFIRM, null, "no-tests"))),
                emit("b-blocker", "test-adequacy", Severity.BLOCKER,
                        List.of(answer("test_situation", Band.CONFIRM, null, "no-tests")))));
        Decisions d = RuleEngine.evaluate(answers, new FactView(), rs, T, 0, MESSAGES);
        assertEquals(1, d.findings().size());
        assertEquals(Severity.BLOCKER, d.findings().get(0).severity());
    }

    @Test
    void emitBeatsSilenceOnSameConcern() {
        Map<String, BandedAnswer> answers = Map.of(
                "test_situation", confirmChoice("test_situation", "no-tests"),
                "breaks_callers", confirmNo("breaks_callers"));
        Ruleset rs = new Ruleset("t", List.of(
                emit("blocker", "test-adequacy", Severity.BLOCKER,
                        List.of(answer("test_situation", Band.CONFIRM, null, "no-tests"))),
                silence("silence-it", "test-adequacy", List.of(answer("breaks_callers", Band.CONFIRM, "no", null)))));
        Decisions d = RuleEngine.evaluate(answers, new FactView(), rs, T, 0, MESSAGES);
        assertEquals(1, d.findings().size(), "an emitted concern is never silenced (I-6)");
        assertFalse(d.silenced().contains("test-adequacy"));
    }

    @Test
    void guardedSilenceIsHonoredWhenNothingEmits() {
        Map<String, BandedAnswer> answers = Map.of("breaks_callers", confirmNo("breaks_callers"));
        Ruleset rs = new Ruleset("t", List.of(
                silence("clean", "breaking-change", List.of(answer("breaks_callers", Band.CONFIRM, "no", null)))));
        Decisions d = RuleEngine.evaluate(answers, new FactView(), rs, T, 0, MESSAGES);
        assertTrue(d.silenced().contains("breaking-change"));
        assertTrue(d.findings().isEmpty());
    }

    @Test
    void unguardedSilenceOnFlagIsInert() {
        // A silence that rests on a sub-CONFIRM answer must NOT silence (I-5).
        Map<String, BandedAnswer> answers = Map.of("breaks_callers", flagChoice("breaks_callers", null));
        Ruleset rs = new Ruleset("t", List.of(
                silence("shaky", "breaking-change", List.of(answer("breaks_callers", Band.FLAG, "no", null)))));
        Decisions d = RuleEngine.evaluate(answers, new FactView(), rs, T, 0, MESSAGES);
        assertFalse(d.silenced().contains("breaking-change"), "silence on a FLAG answer is refused");
    }

    @Test
    void raiseRuleCannotLowerBasePriority() {
        Ruleset rs = new Ruleset("t", List.of(raise("nudge", 1, List.of(fact("has_runtime_change", "true")))));
        FactView facts = new FactView().put("has_runtime_change", true);
        Decisions d = RuleEngine.evaluate(Map.of(), facts, rs, T, 2, MESSAGES);
        assertEquals(2, d.priority(), "raisePriority is monotonic max — it never lowers (I-1/I-2)");
    }

    @Test
    void outcomeIsIndependentOfRuleOrder() {
        Map<String, BandedAnswer> answers = Map.of(
                "test_situation", confirmChoice("test_situation", "no-tests"),
                "api_exposure", new BandedAnswer("api_exposure", Band.CONFIRM, false, null, 4.0, null));
        FactView facts = new FactView().put("is_non_committer", true).put("has_security_file", false);
        List<Rule> rules = new ArrayList<>(List.of(
                emit("t-fyi", "test-adequacy", Severity.FYI, List.of(answer("test_situation", Band.CONFIRM, null, "no-tests"))),
                emit("t-blocker", "test-adequacy", Severity.BLOCKER, List.of(
                        answer("test_situation", Band.CONFIRM, null, "no-tests"), fact("is_non_committer", "true"))),
                raise("k-raise", 2, List.of(answer("api_exposure", Band.CONFIRM, null, null))),
                escalate("esc-test-adequacy", "test-adequacy", List.of(answer("test_situation", Band.CONFIRM, null, "no-tests")))));

        Decisions first = RuleEngine.evaluate(answers, facts, new Ruleset("t", rules), T, 0, MESSAGES);
        Collections.reverse(rules);
        Decisions reversed = RuleEngine.evaluate(answers, facts, new Ruleset("t", rules), T, 0, MESSAGES);

        assertEquals(first.priority(), reversed.priority());
        assertEquals(first.escalations(), reversed.escalations());
        assertEquals(findingKeys(first), findingKeys(reversed), "findings are order-independent (confluence)");
    }

    private static List<String> findingKeys(Decisions d) {
        return d.findings().stream()
                .map(f -> f.concern() + ":" + f.severity())
                .sorted()
                .toList();
    }

    @Test
    void disabledRuleDoesNotFire() {
        Rule disabled = new Rule("off", "test-adequacy", null,
                List.of(answer("test_situation", Band.CONFIRM, null, "no-tests")),
                RuleEffect.emit, Severity.BLOCKER, "msg", null, null, null, null, null, false);
        Decisions d = RuleEngine.evaluate(Map.of("test_situation", confirmChoice("test_situation", "no-tests")),
                new FactView(), new Ruleset("t", List.of(disabled)), T, 0, MESSAGES);
        assertTrue(d.findings().isEmpty());
    }
}
