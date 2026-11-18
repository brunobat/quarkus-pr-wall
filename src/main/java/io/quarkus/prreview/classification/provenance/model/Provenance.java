package io.quarkus.prreview.classification.provenance.model;

import java.time.Instant;
import java.util.List;

/**
 * Who wrote a PR: its author and the other commit authors, each with who they are to the repository, and the PR's AI
 * label. Built only from GitHub and curated lists; it is shown in the report and never read by any decision, nor sent
 * to {@code jev} or Claude.
 *
 * @param author the PR author
 * @param commitAuthors the commits' git authors other than the PR author, in first-seen order; empty when there are
 *        none
 * @param ai the PR's AI label
 * @param readAt when GitHub was read: associations and memberships are GitHub's view at that time
 */
public record Provenance(Person author, List<Person> commitAuthors, AiLabel ai, Instant readAt) {
}
