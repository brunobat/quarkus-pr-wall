package io.quarkus.prreview.triage.model;

import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.pr.model.PullRequest;
import io.quarkus.prreview.report.summary.model.Summary;

/** How one PR ended: classified by {@code jev}, or not classified because of a noted error. */
public sealed interface PrOutcome permits PrOutcome.Classified, PrOutcome.NotClassified {

    int number();

    record Classified(Classification classification, Summary summary) implements PrOutcome {

        @Override
        public int number() {
            return classification.pr().pullRequest().number();
        }
    }

    /**
     * A PR that could not be classified, with the run details known when it failed.
     *
     * @param pullRequest the PR as fetched, or {@code null} when {@code gh} failed
     * @param reason the one-line reason shown after {@code not classified:}, e.g. {@code gh: <message>}
     * @param message the full error message, e.g. {@code jev}'s response body
     * @param jevModel the {@code jev} model, or {@code null} when {@code jev} was not reached
     * @param submissionChars the measured submission size, or {@code null} when not measured
     * @param limit the submission limit, or {@code null} when no submission was measured
     */
    record NotClassified(int number, PullRequest pullRequest, String reason, String message, String jevModel,
            Integer submissionChars, Integer limit) implements PrOutcome {
    }
}
