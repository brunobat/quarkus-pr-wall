package io.quarkus.prreview.triage.model;

import java.util.List;

/** What the classification checks see about one PR: its data and the files it changes. */
public record PrContext(PullRequest pullRequest, List<ChangedFile> changedFiles) {
}
