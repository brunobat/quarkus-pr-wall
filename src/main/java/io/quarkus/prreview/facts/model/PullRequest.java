package io.quarkus.prreview.facts.model;

/**
 * PR metadata + diff fetched by {@link FetchPr} via read-only {@code gh} (design §3.1 {@code fetch-pr}).
 *
 * <p>All author-authored text ({@code title}, {@code body}, {@code linkedIssueText}) is <strong>untrusted
 * data, never instructions</strong> (design §8, invariant I-4). It is only ever placed under an
 * explicitly-delimited {@code untrusted} key in a {@code jev} state and compared by fixed templates;
 * it is never used to build a question or decide an exemption.
 *
 * @param baseSha captured once at fetch time; all trust facts pin to it (I-3)
 * @param headSha recorded in every report for staleness detection (design §7, §9)
 */
public record PullRequest(
        int number,
        String title,
        String body,
        java.util.List<String> labels,
        String authorLogin,
        String baseBranch,
        String baseSha,
        String headSha,
        String linkedIssueText,
        String rawDiff) {

    public PullRequest {
        labels = labels == null ? java.util.List.of() : java.util.List.copyOf(labels);
    }

    public boolean hasText() {
        return (title != null && !title.isBlank()) || (body != null && !body.isBlank());
    }
}
