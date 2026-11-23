package io.quarkus.prreview.triage.model;

/**
 * A pull request as fetched from GitHub: its metadata plus the unified diff.
 *
 * @param draft whether GitHub marks the PR as a draft
 */
public record PullRequest(int number, String title, String url, String state, boolean draft, String diff) {
}
