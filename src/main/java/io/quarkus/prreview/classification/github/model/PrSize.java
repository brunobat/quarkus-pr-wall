package io.quarkus.prreview.classification.github.model;

/**
 * A PR's size, as GitHub reports it.
 *
 * @param changedFiles files changed
 * @param additions lines added
 * @param deletions lines deleted
 */
public record PrSize(int changedFiles, int additions, int deletions) {
}
