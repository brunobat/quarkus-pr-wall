package io.quarkus.prreview.triage.model;

import java.util.List;

import io.quarkus.prreview.classification.github.ci.model.CiChecks;
import io.quarkus.prreview.classification.github.size.model.PrSize;
import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.JevResult;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.pr.model.PullRequest;
import io.quarkus.prreview.report.summary.model.Summary;

/**
 * How one PR ended: one result from each classification source. {@code jev}'s result says whether {@code jev} answered,
 * that is, whether the PR was classified; GitHub's size and CI results are there for every PR, classified or not.
 *
 * @param number the PR number
 * @param checks the ids of {@code jev}'s checks, for the header
 * @param pullRequest the PR as fetched (with an empty diff when only {@code gh pr diff} failed), or {@code null} when
 *        nothing was fetched
 * @param jev {@code jev}'s result
 * @param size GitHub's size facts
 * @param ci GitHub's CI facts
 */
public record PrOutcome(int number, List<String> checks, PullRequest pullRequest, JevResult jev,
        SourceResult<PrSize> size, SourceResult<CiChecks> ci) {

    /** The reason given by a GitHub source that was not asked. */
    public static final String NOT_GATHERED = "not gathered";

    /** A PR {@code jev} classified; the GitHub sources are not gathered. */
    public static PrOutcome answered(Classification classification, Summary summary) {
        PullRequest pr = classification.pr().pullRequest();
        return new PrOutcome(pr.number(), classification.checks().stream().map(CheckResult::checkId).toList(), pr,
                new JevResult.Answered(classification, summary), SourceResult.unavailable(NOT_GATHERED),
                SourceResult.unavailable(NOT_GATHERED));
    }

    /** A PR {@code jev} did not classify; the GitHub sources are not gathered. */
    public static PrOutcome notAnswered(int number, List<String> checks, PullRequest pullRequest, String reason,
            String message, String jevModel, Integer submissionChars, Integer limit) {
        return new PrOutcome(number, checks, pullRequest,
                new JevResult.NotAnswered(reason, message, jevModel, submissionChars, limit),
                SourceResult.unavailable(NOT_GATHERED), SourceResult.unavailable(NOT_GATHERED));
    }
}
