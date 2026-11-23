package io.quarkus.prreview.clients.gh.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One check suite on a commit, as GitHub's REST API reports it.
 *
 * @param status e.g. {@code queued} or {@code completed}
 * @param conclusion e.g. {@code success} or {@code action_required}; {@code null} while not completed
 * @param latestCheckRunsCount how many check runs the suite has
 */
public record GhCheckSuite(String status, String conclusion,
        @JsonProperty("latest_check_runs_count") Integer latestCheckRunsCount) {
}
