package io.quarkus.prreview.classification.jev.model;

import io.quarkus.prreview.report.summary.Summary;

/**
 * What {@code jev} gave for one PR: its answers with their summary, or why it gave none. {@code jev} gives none when
 * the PR could not be fetched, its diff was empty, the submission was over the limit, or {@code jev} itself failed;
 * the report calls such a PR "not classified".
 */
public sealed interface JevResult permits JevResult.Answered, JevResult.NotAnswered {

    record Answered(Classification classification, Summary summary) implements JevResult {
    }

    /**
     * No answers from {@code jev} for this PR, with the run details known when it stopped.
     *
     * @param reason the one-line reason shown after {@code not classified:}, e.g. {@code gh: <message>}
     * @param message the full error message, e.g. {@code jev}'s response body
     * @param jevModel the {@code jev} model, or {@code null} when {@code jev} was not reached
     * @param submissionChars the measured submission size in characters, or {@code null} when not measured
     * @param estimatedTokens the submission's estimated tokens, or {@code null} when not measured
     * @param limit the submission limit in tokens, or {@code null} when no submission was measured
     */
    record NotAnswered(String reason, String message, String jevModel, Integer submissionChars,
            Integer estimatedTokens, Integer limit)
            implements JevResult {
    }
}
