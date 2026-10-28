package io.quarkus.prreview.clients.gh;

import io.quarkus.prreview.pr.model.PullRequest;

/** A {@code gh} invocation failed; the message carries {@code gh}'s own error text. */
public class GhException extends RuntimeException {

    private final PullRequest pullRequest;

    public GhException(String message) {
        this(message, null, null);
    }

    public GhException(String message, Throwable cause) {
        this(message, null, cause);
    }

    /** @param pullRequest the PR's metadata when {@code gh pr view} succeeded and only the diff failed */
    public GhException(String message, PullRequest pullRequest, Throwable cause) {
        super(message, cause);
        this.pullRequest = pullRequest;
    }

    /**
     * The PR's title, URL and state when they were fetched before the failure (with an empty diff), e.g. a diff too
     * large for GitHub; otherwise {@code null}.
     */
    public PullRequest pullRequest() {
        return pullRequest;
    }
}
