package io.quarkus.prreview.review.model;

/**
 * Everything needed to reproduce a verdict (plan §11, design §7 staleness §9): the head SHA reviewed plus
 * every model/config version that fed the decision, so any verdict is bisectable to a specific rule or
 * threshold change.
 *
 * @param headSha                 the PR head SHA the review ran against
 * @param jevModel                the {@code jev} model version from the response (may be null in degraded mode)
 * @param opusModel               the Opus model version used for prose
 * @param triageQuestionVersion   triage question-set version
 * @param rulesetVersion          composite tunable-ruleset version
 * @param thresholdsVersion       thresholds version
 */
public record Provenance(
        String headSha,
        String jevModel,
        String opusModel,
        String triageQuestionVersion,
        String rulesetVersion,
        String thresholdsVersion) {
}
