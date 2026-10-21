package io.quarkus.prreview.interpret.rules.services;



import io.quarkus.prreview.interpret.rules.model.RulePredicate;
import io.quarkus.prreview.interpret.rules.model.Rule;
import io.quarkus.prreview.interpret.rules.model.RuleEffect;
import io.quarkus.prreview.interpret.rules.model.Ruleset;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.interpret.model.Thresholds;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Build-time ruleset validation (plan §5.6, §13). A malformed or unsafe ruleset <strong>fails the
 * build</strong> and never ships. It rejects a rule that:
 * <ul>
 *   <li>references an unknown answer id, fact name, or named threshold,</li>
 *   <li>is a {@code raise}/{@code floor} without a resolvable value,</li>
 *   <li>is an {@code emit} without an emitted severity or a message,</li>
 *   <li>is an {@code escalate} without a target pipeline,</li>
 *   <li>is a {@code silence} without a CONFIRM-clean guard (the I-5 asymmetry, enforced here — the
 *       unguarded-silence vector cannot ship).</li>
 * </ul>
 * The effect verb itself is already closed by {@link RuleEffect} (parsing an unknown verb throws before
 * lint), so a lowering effect is unrepresentable in the first place.
 */
public final class RulesetLint {

    private RulesetLint() {
    }

    /** Validate; returns all errors (empty = valid). The loader throws on a non-empty result. */
    public static List<String> validate(Ruleset ruleset, Thresholds thresholds, Set<String> knownAnswerIds) {
        List<String> errors = new ArrayList<>();
        for (Rule rule : ruleset.rules()) {
            String where = "rule '" + rule.id() + "'";
            if (rule.id() == null || rule.id().isBlank()) {
                errors.add("a rule has no id");
                continue;
            }
            if (rule.then() == null) {
                errors.add(where + " has no effect verb");
                continue;
            }
            validatePredicates(rule, thresholds, knownAnswerIds, errors, where);
            switch (rule.then()) {
                case raisePriority, floorPriority -> {
                    if (rule.value() == null) {
                        errors.add(where + " is " + rule.then() + " but its value could not be resolved"
                                + (rule.valueRef() == null ? " (no value or valueRef)"
                                        : " (unknown threshold '" + rule.valueRef() + "')"));
                    }
                }
                case emit -> {
                    if (rule.severity() == null || !rule.severity().isEmitted()) {
                        errors.add(where + " is emit but has no emitted severity (blocker/needs-attention/fyi)");
                    }
                    if (rule.message() == null || rule.message().isBlank()) {
                        errors.add(where + " is emit but has no message key");
                    }
                }
                case escalate -> {
                    if (rule.pipeline() == null || rule.pipeline().isBlank()) {
                        errors.add(where + " is escalate but names no target pipeline");
                    }
                }
                case silence -> {
                    if (!rule.hasConfirmCleanGuard()) {
                        errors.add(where + " is an UNGUARDED silence — I-5 requires a deterministic fact "
                                + "or a CONFIRM-band answer and no sub-CONFIRM answer predicate");
                    }
                }
            }
        }
        return errors;
    }

    private static void validatePredicates(Rule rule, Thresholds thresholds, Set<String> knownAnswerIds,
            List<String> errors, String where) {
        for (RulePredicate p : rule.when()) {
            boolean isAnswer = p.isAnswerPredicate();
            boolean isFact = p.isFactPredicate();
            if (isAnswer == isFact) {
                errors.add(where + " has a predicate that is neither exactly an answer nor a fact");
                continue;
            }
            if (isAnswer && !knownAnswerIds.contains(p.answer())) {
                errors.add(where + " references unknown answer id '" + p.answer() + "'");
            }
            if (isFact && !io.quarkus.prreview.interpret.services.FactView.KNOWN.contains(p.fact())) {
                errors.add(where + " references unknown fact '" + p.fact() + "'");
            }
        }
        // A named severity must be a legal one (parsing already enforces, kept for defensive clarity).
        if (rule.severity() != null && rule.severity() == Severity.SILENCE && rule.then() == RuleEffect.emit) {
            errors.add(where + " cannot emit severity SILENCE");
        }
    }
}
