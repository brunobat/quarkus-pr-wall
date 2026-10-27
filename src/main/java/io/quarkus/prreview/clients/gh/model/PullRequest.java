package io.quarkus.prreview.clients.gh.model;

/** A pull request as fetched from GitHub: the v1 minimum, metadata plus the unified diff. */
public record PullRequest(int number, String title, String url, String state, String diff) {
}
