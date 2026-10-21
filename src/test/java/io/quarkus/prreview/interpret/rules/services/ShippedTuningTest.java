package io.quarkus.prreview.interpret.rules.services;

import io.quarkus.prreview.interpret.rules.model.Rule;
import io.quarkus.prreview.interpret.rules.model.RuleEffect;
import io.quarkus.prreview.interpret.rules.model.TuningConfig;
import io.quarkus.prreview.questions.services.QuestionRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the <em>shipped</em> tuning surface end to end (plan §12/§13): the real {@code thresholds.yaml}
 * and the {@code rules} block of {@code questions/triage.json} parse into records, {@code valueRef}s
 * resolve, and the default ruleset passes {@link RulesetLint}. If a shipped ruleset were unsafe or
 * malformed, this fails — the build gate the plan requires.
 */
@QuarkusTest
class ShippedTuningTest {

    /** Every concern module except triage; none of them may carry rules (nothing would execute them). */
    private static final List<String> DEEP_MODULES = List.of("test-adequacy", "exempt-class", "breaking-change",
            "test-weakening", "docs-missing", "domain-routing", "inconsistency");

    @Inject
    RulesetLoader loader;

    @Inject
    QuestionRegistry questions;

    @Test
    void shippedTuningLoadsAndLintsClean() {
        TuningConfig tc = loader.get();
        assertNotNull(tc);
        assertNotNull(tc.thresholds());
        assertTrue(tc.thresholds().confirmBand() > tc.thresholds().flagBand());
        assertFalse(tc.ruleset().rules().isEmpty(), "the default ruleset is non-empty");
        assertTrue(tc.ruleset().version().startsWith("triage-rules"),
                "the ruleset records its own version, separate from the wire-visible question version");
    }

    @Test
    void everyRaiseRuleHasAResolvedValue() {
        TuningConfig tc = loader.get();
        for (Rule r : tc.ruleset().rules()) {
            if (r.then() == RuleEffect.raisePriority || r.then() == RuleEffect.floorPriority) {
                assertNotNull(r.value(), "valueRef resolved for " + r.id());
            }
        }
    }

    @Test
    void everyRuleDeclaresAConcernAndAUniqueId() {
        Set<String> ids = new HashSet<>();
        for (Rule r : loader.get().ruleset().rules()) {
            assertNotNull(r.concern(), "rule '" + r.id() + "' declares its concern (no file to inherit it from)");
            assertTrue(ids.add(r.id()), "duplicate rule id '" + r.id() + "' — emit ties break by id");
        }
    }

    @Test
    void theQuestionVersionIsIndependentOfTheRulesVersion() {
        var triage = questions.get("triage");
        assertNotNull(triage.rulesVersion());
        assertFalse(triage.version().equals(triage.rulesVersion()),
                "a re-tuned rule must not look like a re-worded question: the versions are separate");
    }

    @Test
    void onlyTriageCarriesRules() {
        for (String module : DEEP_MODULES) {
            assertNull(questions.get(module).rules(),
                    module + " has no rules — the deep pipelines interpret their answers in Java");
        }
    }
}
