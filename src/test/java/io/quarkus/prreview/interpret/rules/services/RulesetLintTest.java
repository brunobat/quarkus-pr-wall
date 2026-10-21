package io.quarkus.prreview.interpret.rules.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.quarkus.prreview.interpret.rules.model.Rule;
import io.quarkus.prreview.interpret.rules.model.RuleEffect;
import io.quarkus.prreview.interpret.rules.model.RulePredicate;
import io.quarkus.prreview.interpret.rules.model.Ruleset;
import io.quarkus.prreview.interpret.model.Band;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.interpret.model.Thresholds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hostile-ruleset meta-test (plan §13): a tunable edit that tries to erode an invariant must be
 * <em>rejected</em> by {@link RulesetLint} (fails the build), not silently shipped. Complements the
 * engine-level inertness checks in {@link RuleEngineTest}.
 */
class RulesetLintTest {

    private static final Thresholds T = Thresholds.defaults();
    private static final Set<String> KNOWN_IDS = Set.of("test_situation", "breaks_callers", "blast_radius");

    private static List<String> lint(Rule... rules) {
        return RulesetLint.validate(new Ruleset("hostile", List.of(rules)), T, KNOWN_IDS);
    }

    private static RulePredicate answer(String id, Band band, String is, String choice) {
        return new RulePredicate(id, band, is, choice, null, null, null, null);
    }

    private static RulePredicate fact(String name, String equals) {
        return new RulePredicate(null, null, null, null, null, null, name, equals);
    }

    @Test
    void unguardedSilenceIsRejected() {
        Rule hostile = new Rule("sneaky-silence", "breaking-change", null,
                List.of(answer("breaks_callers", Band.FLAG, "yes", null)),   // sub-CONFIRM — not a clean guard
                RuleEffect.silence, null, null, null, null, null, null, null, true);
        assertFalse(lint(hostile).isEmpty(), "silence on a FLAG answer must fail the build");
    }

    @Test
    void silenceWithNoPredicatesIsRejected() {
        Rule hostile = new Rule("blanket-silence", "test-adequacy", null,
                List.of(), RuleEffect.silence, null, null, null, null, null, null, null, true);
        assertFalse(lint(hostile).isEmpty(), "an unconditional silence must fail the build");
    }

    @Test
    void unknownAnswerIdIsRejected() {
        Rule bad = new Rule("typo", "test-adequacy", null,
                List.of(answer("test_situaton", Band.CONFIRM, null, "no-tests")), // typo'd slug
                RuleEffect.emit, Severity.BLOCKER, "m", null, null, null, null, null, true);
        assertFalse(lint(bad).isEmpty());
    }

    @Test
    void unknownFactIsRejected() {
        Rule bad = new Rule("ghost-fact", "test-adequacy", null,
                List.of(fact("author_is_trusted", "true")),
                RuleEffect.emit, Severity.FYI, "m", null, null, null, null, null, true);
        assertFalse(lint(bad).isEmpty(), "a trust fact that does not exist must not be referenceable");
    }

    @Test
    void emitWithoutSeverityIsRejected() {
        Rule bad = new Rule("no-sev", "test-adequacy", null,
                List.of(answer("test_situation", Band.CONFIRM, null, "no-tests")),
                RuleEffect.emit, null, "m", null, null, null, null, null, true);
        assertFalse(lint(bad).isEmpty());
    }

    @Test
    void raiseWithoutValueIsRejected() {
        Rule bad = new Rule("no-value", "review-priority", null,
                List.of(fact("has_security_file", "true")),
                RuleEffect.raisePriority, null, null, null, null, null, null, null, true);
        assertFalse(lint(bad).isEmpty());
    }

    @Test
    void wellFormedGuardedSilencePasses() {
        Rule ok = new Rule("clean", "breaking-change", null,
                List.of(answer("breaks_callers", Band.CONFIRM, "no", null)),
                RuleEffect.silence, null, null, null, null, null, null, null, true);
        assertTrue(lint(ok).isEmpty());
    }

    /**
     * A misspelled key must fail the load, not vanish. {@code "sevrity"} on an emit rule would otherwise
     * leave {@code severity} null — and the typo'd rule would be reported as malformed only by luck.
     * This guards the absence of {@code @JsonIgnoreProperties(ignoreUnknown = true)} on {@link Rule},
     * which is what {@link RulesetLoader}'s strict mapper relies on.
     */
    @Test
    void aMisspelledRuleKeyIsRejectedNotSwallowed() {
        ObjectMapper strict = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        String typo = """
                {"id": "oops", "concern": "test-adequacy", "then": "emit", "sevrity": "blocker"}""";
        assertThrows(JsonProcessingException.class, () -> strict.readValue(typo, Rule.class));
    }

    @Test
    void factBasedSilencePasses() {
        Rule ok = new Rule("exempt", "test-adequacy", null,
                List.of(fact("all_runtime_exempt", "true")),
                RuleEffect.silence, null, null, null, null, null, null, null, true);
        assertTrue(lint(ok).isEmpty(), "a deterministic-fact silence is the safest basis of all");
    }

    // --- I-7: a voluntary declaration is never penalized -------------------------------------------

    /**
     * The rule that would do the real damage. It passes every other check — known fact, resolvable
     * value, legal verb — and it would make candour about tooling cost a priority point. Nothing but
     * this lint stands between it and the shipped ruleset, because rules are data.
     */
    @Test
    void raisingPriorityOnADeclarationIsRejected() {
        Rule hostile = new Rule("punish-the-honest", "authorship-provenance", null,
                List.of(fact("declares_ai_assistance", "true")),
                RuleEffect.raisePriority, null, null, null, 2, null, null, null, true);

        List<String> errors = lint(hostile);

        assertFalse(errors.isEmpty(), "a declaration must never raise priority");
        assertTrue(errors.getFirst().contains("I-7"), errors.toString());
    }

    @Test
    void emittingAFindingOnADeclarationIsRejected() {
        Rule hostile = new Rule("name-and-shame", "authorship-provenance", null,
                List.of(fact("declares_ai_assistance", "true")),
                RuleEffect.emit, Severity.FYI, "authorship.declared", null, null, null, null, null, true);

        assertFalse(lint(hostile).isEmpty(), "a declaration must never produce a finding of its own");
    }

    /** Silencing on a declaration is the mirror-image abuse: it would reward hiding the marker. */
    @Test
    void silencingOnADeclarationIsRejected() {
        Rule hostile = new Rule("declared-so-skip-it", "test-adequacy", null,
                List.of(fact("declares_ai_assistance", "true")),
                RuleEffect.silence, null, null, null, null, null, null, null, true);

        assertFalse(lint(hostile).isEmpty());
    }

    /**
     * Escalation looks like the one harmless thing a declaration could do, and it is not. A deep pipeline
     * is skipped only for a concern triage already settled, so this rule changes the outcome exactly when
     * test-adequacy had been silenced or already emitted — and {@code FindingCombiner} keeps the most
     * severe finding per concern, so the extra run can only add one or worsen one. The declared PR and its
     * undeclared twin then differ solely in the declared one's exposure to a finding. Hence the ban is on
     * reading the fact, not on the verb: there is no predicate that keeps a benefit this rule never had.
     */
    @Test
    void escalatingOnADeclarationIsRejectedToo() {
        Rule hostile = new Rule("look-at-the-tests", "authorship-provenance", null,
                List.of(fact("declares_ai_assistance", "true"), fact("has_runtime_change", "true")),
                RuleEffect.escalate, null, null, "test-adequacy", null, null, null, null, true);

        List<String> errors = lint(hostile);

        assertFalse(errors.isEmpty(), "an escalation keyed on a declaration is still a penalty for candour");
        assertTrue(errors.getFirst().contains("I-7"), errors.toString());
    }

    /**
     * The ban is on the declaration alone. What kind of <em>account</em> opened a PR is public, unfalsifiable
     * metadata that routine-automation routing legitimately depends on — unlike a marker the author chose to
     * leave. Pinned so a later tightening of I-7 cannot quietly take the bot facts with it.
     */
    @Test
    void aBotFactRemainsReadableOutsideTheSignalToNoiseConcern() {
        Rule ok = new Rule("route-routine-automation", "test-adequacy", null,
                List.of(fact("author_is_allowlisted_bot", "true")),
                RuleEffect.escalate, null, null, "exempt-class", null, null, null, null, true);

        assertTrue(lint(ok).isEmpty(), lint(ok).toString());
    }

    @Test
    void aSignalToNoiseRuleMayNotReadAnAuthorshipFact() {
        Rule hostile = new Rule("padded-therefore-generated", "policy-signal-to-noise", null,
                List.of(fact("author_is_bot", "true")),
                RuleEffect.escalate, null, null, "inconsistency", null, null, null, null, true);

        assertFalse(lint(hostile).isEmpty(),
                "description quality must not be corroborated by who or what opened the PR");
    }

    @Test
    void anAuthorshipRuleMayNotReadTheSignalToNoiseAnswer() {
        Rule hostile = new Rule("generated-because-padded", "authorship-provenance", null,
                List.of(answer("signal_to_noise_violation", Band.CONFIRM, "yes", null)),
                RuleEffect.escalate, null, null, "test-adequacy", null, null, null, null, true);

        List<String> errors = RulesetLint.validate(new Ruleset("hostile", List.of(hostile)), T,
                Set.of("signal_to_noise_violation"));

        assertFalse(errors.isEmpty());
    }
}
