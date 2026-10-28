package io.quarkus.prreview.triage.model;

import java.util.List;

import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.pr.model.PullRequest;
import io.quarkus.prreview.report.summary.model.Summary;

/** How one PR ended: classified by {@code jev}, or not classified because of a noted error. */
public sealed interface PrOutcome permits PrOutcome.Classified, PrOutcome.NotClassified {

    record Classified(Classification classification, Summary summary) implements PrOutcome {
    }

    /**
     * A PR that could not be classified, with the run details known when it failed.
     *
     * @param checks the ids of the checks that would have classified it, for the header
     * @param pullRequest the PR as fetched (with an empty diff when only {@code gh pr diff} failed), or {@code null}
     *        when nothing was fetched
     * @param reason the one-line reason shown after {@code not classified:}, e.g. {@code gh: <message>}
     * @param message the full error message, e.g. {@code jev}'s response body
     * @param jevModel the {@code jev} model, or {@code null} when {@code jev} was not reached
     * @param submissionChars the measured submission size, or {@code null} when not measured
     * @param limit the submission limit, or {@code null} when no submission was measured
     */
    record NotClassified(int number, List<String> checks, PullRequest pullRequest, String reason, String message, String jevModel,
            Integer submissionChars, Integer limit) implements PrOutcome {
    }
}
