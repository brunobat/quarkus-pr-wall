package io.quarkus.prreview.triage.model;

import java.util.List;

import io.quarkus.prreview.classification.SourceResult;
import io.quarkus.prreview.classification.github.model.Approval;
import io.quarkus.prreview.classification.github.model.CiChecks;
import io.quarkus.prreview.classification.github.model.PrSize;
import io.quarkus.prreview.classification.jev.model.JevResult;
import io.quarkus.prreview.classification.provenance.model.Provenance;

/**
 * How one PR ended: one result from each classification source. {@code jev}'s result says whether {@code jev} answered,
 * that is, whether the PR was classified; GitHub's size, CI and provenance results are there for every PR, classified or
 * not.
 *
 * @param number the PR number
 * @param checks the ids of {@code jev}'s checks, for the header
 * @param pullRequest the PR as fetched (with an empty diff when only {@code gh pr diff} failed), or {@code null} when
 *        nothing was fetched
 * @param jev {@code jev}'s result
 * @param size GitHub's size facts
 * @param ci GitHub's CI facts
 * @param provenance who wrote the PR, from GitHub's facts
 */
public record PrOutcome(int number, List<String> checks, PullRequest pullRequest, JevResult jev,
        SourceResult<PrSize> size, SourceResult<CiChecks> ci, SourceResult<Provenance> provenance) {

    /** Whether GitHub marks the PR as a draft; {@code false} when its metadata was not read. */
    public boolean draft() {
        return pullRequest != null && pullRequest.draft();
    }

    /** Whether GitHub marks a check suite on the head commit as awaiting approval; {@code false} when unknown. */
    public boolean waitingForApproval() {
        return ci instanceof SourceResult.Available<CiChecks> available
                && available.facts().approval() == Approval.WAITING;
    }
}
