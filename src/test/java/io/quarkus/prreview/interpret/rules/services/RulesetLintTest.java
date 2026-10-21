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
}
