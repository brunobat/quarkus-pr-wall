package io.quarkus.prreview.clients.gh.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A PR's head commit and the checks on it, as {@code gh pr view} reports them.
 *
 * @param headRefOid the head commit's full SHA
 * @param rollup every check run and commit status on the head commit, in GitHub's order; empty when there are none
 */
public record GhChecks(String headRefOid, @JsonProperty("statusCheckRollup") List<GhCheck> rollup) {
}
