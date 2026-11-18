package io.quarkus.prreview.classification.model;

import io.quarkus.prreview.report.summary.model.Summary;

/** What {@code jev} gave for one PR: its answers with their summary, or why the PR was not classified. */
public sealed interface JevResult permits JevResult.Classified, JevResult.NotClassified {

    record Classified(Classification classification, Summary summary) implements JevResult {
    }

    /**
     * A PR {@code jev} did not classify, with the run details known when it stopped.
     *
     * @param reason the one-line reason shown after {@code not classified:}, e.g. {@code gh: <message>}
     * @param message the full error message, e.g. {@code jev}'s response body
     * @param jevModel the {@code jev} model, or {@code null} when {@code jev} was not reached
     * @param submissionChars the measured submission size, or {@code null} when not measured
     * @param limit the submission limit, or {@code null} when no submission was measured
     */
    record NotClassified(String reason, String message, String jevModel, Integer submissionChars, Integer limit)
            implements JevResult {
    }
}
