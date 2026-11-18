package io.quarkus.prreview.clients.gh.model;

/**
 * A PR's size as {@code gh pr view} reports it.
 *
 * @param additions lines added
 * @param deletions lines deleted
 * @param changedFiles files changed
 */
public record GhSize(int additions, int deletions, int changedFiles) {
}
