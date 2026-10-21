package io.quarkus.prreview.facts.model;

import java.util.List;

/**
 * What the PR itself <em>says</em> about how it was produced (authorship-provenance design §4.1).
 *
 * <p>Every field here is an observation, not an inference. The agent does not guess whether code was
 * written by a person, and nothing in this record is evidence that it was not: the only AI-assistance
 * signal available is a marker the author chose to leave, and most assisted PRs leave none. "No
 * declaration" therefore carries no information at all and is never rendered, never scored, and never
 * read by a rule.
 *
 * <p>Invariant I-7 — <em>a voluntary declaration is never penalized</em>. {@link #declaresAiAssistance}
 * may only route a PR to a deeper look; it may not raise priority, emit a finding, or lower trust.
 * {@code RulesetLint} enforces this against the shipped ruleset, so the guarantee survives tuning.
 *
 * @param authorIsBot             GitHub's own {@code __typename == "Bot"} classification of the PR author
 * @param authorIsAllowlistedBot  the author is a routine-automation account from {@code benign-bots.txt}
 * @param declaresAiAssistance    a commit trailer or the PR body carries a marker from
 *                                {@code ai-provenance-signals.txt}
 * @param hasHumanCommitAuthor    at least one commit was authored by a resolved, non-bot GitHub account —
 *                                relevant because a bot-authored PR can still carry human commits
 * @param docsAddedLines          added lines in docs-tagged files
 * @param codeAddedLines          added lines in runtime- and test-tagged files
 * @param completedChecklistItems {@code - [x]} boxes ticked in the PR body
 * @param commits                 the recorded commit metadata these booleans were derived from
 */
public record AuthorshipProvenance(
        boolean authorIsBot,
        boolean authorIsAllowlistedBot,
        boolean declaresAiAssistance,
        boolean hasHumanCommitAuthor,
        int docsAddedLines,
        int codeAddedLines,
        int completedChecklistItems,
        List<CommitMeta> commits) {

    public AuthorshipProvenance {
        commits = commits == null ? List.of() : List.copyOf(commits);
    }

    /** The degraded value used when provenance could not be resolved: says nothing about anything. */
    public static AuthorshipProvenance unknown() {
        return new AuthorshipProvenance(false, false, false, false, 0, 0, 0, List.of());
    }
}
