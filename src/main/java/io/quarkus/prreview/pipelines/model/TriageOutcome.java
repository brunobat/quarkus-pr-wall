package io.quarkus.prreview.pipelines.model;

import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.rules.model.Decisions;
import io.quarkus.prreview.interpret.rules.model.TuningConfig;
import io.quarkus.prreview.jev.model.JevResponse;

import java.util.Map;

/**
 * The result of the triage pass (plan §5): the banded answers, the engine {@link Decisions} (priority,
 * findings, escalations, silenced), and the effective blast radius used for range ranking (§7, §10).
 *
 * @param answers              every triage answer after the confidence gate
 * @param decisions            the rule-engine output (escalations already include the I-5 low-confidence set)
 * @param effectiveBlastRadius the blast_radius answer, raised to {@code blastRadiusFloor} when it or
 *                             change_kind is sub-CONFIRM (I-5)
 * @param questionSetVersion   triage question-set version, for report provenance (§11)
 * @param tuning               the ruleset + thresholds used, for provenance (§11)
 * @param raw                  the raw jev response (reporting only, never a second gate decision)
 */
public record TriageOutcome(
        Map<String, BandedAnswer> answers,
        Decisions decisions,
        int effectiveBlastRadius,
        String questionSetVersion,
        TuningConfig tuning,
        JevResponse raw) {
}
