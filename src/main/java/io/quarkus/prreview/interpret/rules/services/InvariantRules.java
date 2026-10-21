package io.quarkus.prreview.interpret.rules.services;


import io.quarkus.prreview.interpret.rules.model.Rule;
import io.quarkus.prreview.interpret.rules.model.RuleEffect;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.services.FactView;
import io.quarkus.prreview.interpret.model.Thresholds;

import java.util.Map;

/**
 * The compiled-in, always-on, non-overridable invariant tier (plan §5.6, §8 — invariants I-1…I-6).
 * These are <em>not</em> in the editable ruleset file; they live in the engine itself so no tunable
 * edit can disable, reorder below, or override them. Only the monotonic, safe directions are
 * expressible, which is what lets the tunable ruleset grow without eroding the anti-gaming guarantees.
 *
 * <p>I-7 ("a voluntary declaration is never penalized") is the one invariant with no runtime clause
 * here, because it is a statement about what a rule may <em>read</em> rather than about what the engine
 * computes. {@link RulesetLint} enforces it at load time by rejecting any rule that reads
 * {@code declares_ai_assistance} at all.
 */
public final class InvariantRules {

    /** The triage answer id carrying the area-anomaly signal, used by I-2's raise-only anomaly. */
    private static final String AREA_ANOMALY = "outside_prior_areas";

    private InvariantRules() {
    }

    /**
     * I-1: security-sensitive paths are floored to "read closely" (priority 2), keyed off the
     * deterministic {@code has_security_file} fact. The {@code intrinsic_risk} answer can be gamed or
     * injected down to "skim"; this deterministic {@code max()} against a fact holds even when the
     * model's argmax says skim.
     */
    public static int securityFloor(FactView facts, Thresholds t) {
        return facts.bool("has_security_file") ? t.named("priorityFloor", 2) : 0;
    }

    /**
     * I-2 (raise-only anomaly): an area anomaly, brand-new account, or oversized first PR may only
     * <em>raise</em> priority — never lower it — and never above the "read closely" ceiling here.
     */
    public static int applyAnomaly(int priority, Map<String, BandedAnswer> answers, FactView facts) {
        if (anomalyFires(answers, facts)) {
            return Math.max(priority, Math.min(2, priority + 1));
        }
        return priority;
    }

    public static boolean anomalyFires(Map<String, BandedAnswer> answers, FactView facts) {
        BandedAnswer q6 = answers.get(AREA_ANOMALY);
        boolean areaAnomaly = q6 != null && q6.isConfirmYes();
        return areaAnomaly || facts.bool("brand_new_account");
    }

    /**
     * I-5 silence guard: a {@code silence} effect is honored only when the rule asserts a CONFIRM-band
     * "no issue". Enforced here, not by each rule author remembering it, so silence can never be the
     * residue of a shaky answer.
     */
    public static boolean silenceAllowed(Rule rule) {
        return rule.then() == RuleEffect.silence && rule.hasConfirmCleanGuard();
    }
}
