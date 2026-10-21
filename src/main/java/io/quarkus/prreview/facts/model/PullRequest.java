package io.quarkus.prreview.facts.model;

/**
 * PR metadata + diff fetched by {@link FetchPr} via read-only {@code gh} (design §3.1 {@code fetch-pr}).
 *
 * <p>All author-authored text ({@code title}, {@code body}, {@code linkedIssueText}) is <strong>untrusted
 * data, never instructions</strong> (design §8, invariant I-4). It is only ever placed under an
 * explicitly-delimited {@code untrusted} key in a {@code jev} state and compared by fixed templates;
 * it is never used to build a question or decide an exemption.
 *
 * @param authorIsBot GraphQL {@code author.__typename == "Bot"} — GitHub's own classification, not a
 *                    login-suffix guess. An account type, carrying no judgment about the PR's quality.
 * @param baseSha     captured once at fetch time; all trust facts pin to it (I-3)
 * @param headSha     recorded in every report for staleness detection (design §7, §9)
 * @param commits     the branch's commits, newest last, capped by the fetch; empty when the PR has none
 *                    visible. Provenance metadata only (authorship-provenance design §4.1).
 */
public record PullRequest(
        int number,
        String title,
        String body,
        java.util.List<String> labels,
        String authorLogin,
        boolean authorIsBot,
        String baseBranch,
        String headBranch,
        String baseSha,
        String headSha,
        String linkedIssueText,
        java.util.List<CommitMeta> commits,
        String rawDiff) {

    public PullRequest {
        labels = labels == null ? java.util.List.of() : java.util.List.copyOf(labels);
        commits = commits == null ? java.util.List.of() : java.util.List.copyOf(commits);
    }

    public boolean hasText() {
        return (title != null && !title.isBlank()) || (body != null && !body.isBlank());
    }

    /**
     * The same PR carrying its diff. The bulk GraphQL fetch returns metadata for a whole sweep in one
     * call but cannot return diffs, so {@link FetchPr} pairs cached metadata with a per-PR
     * {@code gh pr diff} here.
     */
    public PullRequest withDiff(String diff) {
        return new PullRequest(number, title, body, labels, authorLogin, authorIsBot, baseBranch,
                headBranch, baseSha, headSha, linkedIssueText, commits, diff);
    }
}
