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
 *       unguarded-silence vector cannot ship),</li>
 *   <li>reads {@code declares_ai_assistance} at all, whatever the effect (I-7: a voluntary declaration
 *       reaches the report and nothing else),</li>
 *   <li>mixes the two authorship concerns — what a PR says about its tooling and whether its
 *       description is padded are separate questions, and a rule that reads both would let one
 *       stand in as evidence for the other.</li>
 * </ul>
 * The effect verb itself is already closed by {@link RuleEffect} (parsing an unknown verb throws before
 * lint), so a lowering effect is unrepresentable in the first place.
 */
public final class RulesetLint {

    /** The voluntary declaration. No tunable rule may read it (I-7). */
    private static final String DECLARES_AI_ASSISTANCE = "declares_ai_assistance";

    /** Everything the facts layer derives from authorship, as opposed to from the diff. */
    private static final Set<String> PROVENANCE_FACTS =
            Set.of("author_is_bot", "author_is_allowlisted_bot", DECLARES_AI_ASSISTANCE);

    private static final String AUTHORSHIP_CONCERN = "authorship-provenance";
    private static final String SIGNAL_TO_NOISE_CONCERN = "policy-signal-to-noise";
    private static final String SIGNAL_TO_NOISE_ANSWER = "signal_to_noise_violation";

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
            validateAuthorshipAsymmetry(rule, errors, where);
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

    /**
     * The I-7 asymmetry, plus the firewall between the two authorship concerns.
     *
     * <p>Both checks exist because the failure they prevent is silent. A rule that raises priority on
     * {@code declares_ai_assistance} would punish exactly the contributors who were candid about their
     * tooling, and it would do so while every test still passed — the ruleset is data, so nothing else
     * would notice. Likewise a rule that treats "the description is padded" as corroboration for "this
     * was AI-assisted" quietly turns a writing-style observation into an authorship accusation.
     *
     * <p>The declaration check bans the fact outright rather than allowing a benign-looking
     * {@code escalate}. Escalating is not benign here: a deep pipeline is skipped only for a concern
     * triage already settled, so an escalation keyed on the declaration takes effect precisely when the
     * concern had been silenced or already emitted, and {@code FindingCombiner} keeps the most severe
     * finding per concern — so the extra run can only add a finding or worsen one. There is no predicate
     * that keeps the benefit and drops the penalty, because there was never a benefit: the declared PR
     * and its undeclared twin differ only in the declared one's exposure to a finding.
     */
    private static void validateAuthorshipAsymmetry(Rule rule, List<String> errors, String where) {
        boolean readsDeclaration = false;
        boolean readsProvenance = false;
        boolean readsSignalToNoise = false;
        for (RulePredicate p : rule.when()) {
            if (p.isFactPredicate() && PROVENANCE_FACTS.contains(p.fact())) {
                readsProvenance = true;
                readsDeclaration |= DECLARES_AI_ASSISTANCE.equals(p.fact());
            }
            readsSignalToNoise |= p.isAnswerPredicate() && SIGNAL_TO_NOISE_ANSWER.equals(p.answer());
        }
        if (readsDeclaration) {
            errors.add(where + " reads '" + DECLARES_AI_ASSISTANCE + "' — I-7 allows no rule to read it, "
                    + "not even to " + rule.then() + ": every effect a declaration can have on a PR is a "
                    + "penalty for having declared. The fact is for the report only");
        }
        if (readsProvenance && SIGNAL_TO_NOISE_CONCERN.equals(rule.concern())) {
            errors.add(where + " is in '" + SIGNAL_TO_NOISE_CONCERN + "' but reads an authorship fact — "
                    + "description quality and authorship must not corroborate each other");
        }
        if (readsSignalToNoise && AUTHORSHIP_CONCERN.equals(rule.concern())) {
            errors.add(where + " is in '" + AUTHORSHIP_CONCERN + "' but reads '" + SIGNAL_TO_NOISE_ANSWER
                    + "' — description quality and authorship must not corroborate each other");
        }
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
