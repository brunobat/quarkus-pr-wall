package io.quarkus.prreview.classification.github.ci.model;

import java.util.List;

/**
 * The checks on a PR's head commit, as GitHub reports them.
 *
 * @param headCommit the head commit's full SHA
 * @param checks every check run and commit status on the head commit, in GitHub's order
 * @param notPassed the checks whose value is not {@code SUCCESS}, {@code SKIPPED} or {@code NEUTRAL}, in GitHub's order
 * @param approval whether a check suite on the head commit awaits approval, as GitHub marks it
 */
public record CiChecks(String headCommit, List<CiCheck> checks, List<FailedCheck> notPassed, Approval approval) {
}
