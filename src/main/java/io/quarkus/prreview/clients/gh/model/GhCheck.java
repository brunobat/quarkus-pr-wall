package io.quarkus.prreview.clients.gh.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of a PR's check rollup, as {@code gh pr view --json statusCheckRollup} returns it. A {@code CheckRun} has
 * a name, workflow name, status, conclusion and details URL; a {@code StatusContext} (a commit status) has a context,
 * state and target URL. The fields of the other kind are {@code null}.
 *
 * @param typename {@code CheckRun} or {@code StatusContext}
 * @param conclusion empty while the check run has not completed
 */
public record GhCheck(@JsonProperty("__typename") String typename, String name, String workflowName, String status,
        String conclusion, String detailsUrl, String context, String state, String targetUrl) {

    public static final String CHECK_RUN = "CheckRun";
    public static final String STATUS_CONTEXT = "StatusContext";
}
