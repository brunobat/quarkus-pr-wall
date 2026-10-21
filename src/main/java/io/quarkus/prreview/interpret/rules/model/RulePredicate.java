package io.quarkus.prreview.interpret.rules.model;

import io.quarkus.prreview.interpret.model.Band;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.services.FactView;

import java.util.Map;

/**
 * One predicate in a rule's {@code when} clause (plan §5.6). It reads exactly one of two sources:
 * a <strong>banded {@code jev} answer</strong> ({@code answer} + {@code band}/{@code is}/{@code choice}/
 * {@code scoreAtLeast}/{@code scoreAtMost}) or a <strong>deterministic fact</strong> ({@code fact} +
 * {@code equals}). It can never read another pipeline's output or raw PR text — the engine does not
 * expose those as predicate sources. Unknown keys are rejected at load time, not ignored (see {@link Rule}).
 */
public record RulePredicate(
        String answer,
        Band band,
        String is,
        String choice,
        Integer scoreAtLeast,
        Integer scoreAtMost,
        String fact,
        String equals) {

    public boolean isAnswerPredicate() {
        return answer != null;
    }

    public boolean isFactPredicate() {
        return fact != null;
    }

    boolean holds(Map<String, BandedAnswer> answers, FactView facts) {
        if (isFactPredicate()) {
            return facts.matches(fact, equals == null ? "true" : equals);
        }
        BandedAnswer a = answers.get(answer);
        if (a == null) {
            return false;
        }
        if (band != null && a.band() != band) {
            return false;
        }
        if (is != null) {
            boolean wantYes = is.equalsIgnoreCase("yes") || is.equalsIgnoreCase("true");
            if (a.yes() != wantYes) {
                return false;
            }
        }
        if (choice != null && !a.isChoice(choice)) {
            return false;
        }
        if (scoreAtLeast != null && !(a.scoreInt() >= scoreAtLeast)) {
            return false;
        }
        if (scoreAtMost != null && !(a.scoreInt() <= scoreAtMost)) {
            return false;
        }
        return true;
    }
}
