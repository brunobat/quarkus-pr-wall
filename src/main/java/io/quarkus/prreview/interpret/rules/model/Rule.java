package io.quarkus.prreview.interpret.rules.model;

import io.quarkus.prreview.interpret.model.Band;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.services.FactView;
import io.quarkus.prreview.interpret.model.Severity;

import java.util.List;
import java.util.Map;

/**
 * A single declarative rule (plan §5.6) — data, not code. It is read from the {@code rules} block of the
 * question set whose ids it references. A rule may be {@code enabled: false} to keep it for provenance /
 * A-B comparison rather than deleting it; {@code note} carries the rationale JSON cannot put in a comment.
 *
 * <p>Deliberately <em>not</em> {@code @JsonIgnoreProperties(ignoreUnknown = true)}: the loader parses rules
 * with a strict mapper, so a misspelled key ({@code sevrity}) fails the load instead of being silently
 * dropped — a swallowed key would downgrade a blocker to nothing with no diagnostic at all.
 */
public record Rule(
        String id,
        String concern,
        String note,
        List<RulePredicate> when,
        RuleEffect then,
        Severity severity,
        String message,
        String pipeline,
        Integer value,
        String valueRef,
        String file,
        Integer line,
        Boolean enabled) {

    public Rule {
        when = when == null ? List.of() : List.copyOf(when);
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** Replace {@code value} with the resolved named-threshold integer (loader resolves {@code valueRef}). */
    public Rule withValue(Integer v) {
        return new Rule(id, concern, note, when, then, severity, message, pipeline, v, valueRef, file, line,
                enabled);
    }

    /** All predicates must hold (logical AND) — plan §5.6. */
    public boolean matches(Map<String, BandedAnswer> answers, FactView facts) {
        for (RulePredicate p : when) {
            if (!p.holds(answers, facts)) {
                return false;
            }
        }
        return true;
    }

    /** True if this rule is triggered by at least one FLAG-band answer — the finding is then tentative. */
    public boolean triggeredAtFlag(Map<String, BandedAnswer> answers) {
        for (RulePredicate p : when) {
            if (p.isAnswerPredicate() && p.band() == Band.FLAG) {
                return true;
            }
            if (p.isAnswerPredicate()) {
                BandedAnswer a = answers.get(p.answer());
                if (a != null && a.band() == Band.FLAG) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * I-5 silence guard. A silence is honored only when it rests on confident, non-gameable evidence:
     * the rule must have at least one predicate, and <em>no</em> answer predicate may be sub-CONFIRM
     * (FLAG/HUMAN). A silence may therefore rest on a deterministic fact or on CONFIRM-band answers
     * (whatever "clean" means for that question — a {@code none} choice, an adequate score, a noul "yes"),
     * but never on a shaky answer. The engine enforces this; a rule author cannot opt out.
     */
    public boolean hasConfirmCleanGuard() {
        boolean hasGuard = false;
        for (RulePredicate p : when) {
            if (p.isAnswerPredicate()) {
                if (p.band() != Band.CONFIRM) {
                    return false;
                }
                hasGuard = true;
            } else if (p.isFactPredicate()) {
                hasGuard = true;
            }
        }
        return hasGuard;
    }
}
