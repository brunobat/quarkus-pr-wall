package io.quarkus.prreview.interpret.model;

import io.quarkus.prreview.jev.model.Answer;

/**
 * A {@code jev} {@link Answer} after the {@link ConfidenceGate} has banded it (plan §6). This is the
 * only view of an answer the rule engine and the deep-pipeline interpretation functions read — the raw
 * probabilities never leak past the gate, so policy sees a stable CONFIRM/FLAG/HUMAN + typed value.
 *
 * @param id    the question id (e.g. {@code "breaks_callers"})
 * @param band  the confidence band
 * @param yes   for a noul answer, whether the probability leans "yes" ({@code noul ≥ 0.5}); false for
 *              choice/score
 * @param choice for a choice answer, the selected option; null otherwise
 * @param score  for a score answer, the probability-weighted mean; NaN otherwise
 * @param raw    the underlying answer (for reporting, never for a second gate decision)
 */
public record BandedAnswer(
        String id,
        Band band,
        boolean yes,
        String choice,
        double score,
        Answer raw) {

    public boolean isConfirm() {
        return band == Band.CONFIRM;
    }

    public boolean isConfirmYes() {
        return band == Band.CONFIRM && yes;
    }

    public boolean isConfirmNo() {
        return band == Band.CONFIRM && !yes;
    }

    public boolean atLeastFlag() {
        return band == Band.FLAG || band == Band.HUMAN;
    }

    public boolean isChoice(String option) {
        return option.equals(choice);
    }

    public int scoreInt() {
        return (int) Math.round(score);
    }
}
