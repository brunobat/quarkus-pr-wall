package io.quarkus.prreview.facts.model;

import java.util.Locale;

/**
 * One commit on the PR branch, as returned by the bulk GraphQL fetch (authorship-provenance design §4.1).
 *
 * <p>This is raw recorded metadata, not a judgment. Tier 1 reads {@link #committerName} and
 * {@link #messageBody}; Tier 2 (specified, unimplemented — design §3.2) is the reason the dates,
 * {@link #parentCount} and the headline are stored at all. Nothing here is interpreted in the facts layer
 * beyond the single boolean {@link AuthorshipProvenance#declaresAiAssistance()}.
 *
 * <p>{@code committerName} is the runtime that created the commit object. Unlike a trailer it cannot be
 * removed without rewriting history, which makes it the strongest single provenance signal — and it is why
 * the fetch moved to GraphQL, since {@code gh pr view --json commits} does not expose it (design §3.1).
 *
 * @param oid             the commit SHA
 * @param messageHeadline first line of the commit message
 * @param messageBody     the rest of the commit message — where co-author trailers live
 * @param authoredDate    ISO-8601; equal to {@code committedDate} on any plain {@code git commit}
 * @param committedDate   ISO-8601; diverges from {@code authoredDate} only on rebase/amend/cherry-pick
 * @param parentCount     2 or more means a merge commit — a fact, not the headline heuristic it replaced
 * @param authorName      {@code commit.author.name}; a display string, <strong>never</strong> an identity key
 * @param authorLogin     the GitHub login GitHub itself resolved the author to, or empty
 * @param committerName   {@code commit.committer.name}
 * @param committerLogin  the GitHub login GitHub itself resolved the committer to, or empty
 */
public record CommitMeta(
        String oid,
        String messageHeadline,
        String messageBody,
        String authoredDate,
        String committedDate,
        int parentCount,
        String authorName,
        String authorLogin,
        String committerName,
        String committerLogin) {

    public boolean isMergeCommit() {
        return parentCount > 1;
    }

    /**
     * A coarse shape key for the headline, for the Tier 2 "every commit message matches one template"
     * signal (design §3.2). Digits, hex blobs and quoted spans collapse, so {@code Bump foo from 1.2 to
     * 1.3} and {@code Bump bar from 4.0 to 4.1} hash alike. Derived rather than stored: it is a pure
     * function of {@link #messageHeadline}, and keeping it so means there is no normalization table to rot.
     */
    public String headlineTemplateHash() {
        if (messageHeadline == null || messageHeadline.isBlank()) {
            return "";
        }
        String shape = messageHeadline.toLowerCase(Locale.ROOT)
                .replaceAll("`[^`]*`", "§")
                .replaceAll("\"[^\"]*\"", "§")
                .replaceAll("\\b[0-9a-f]{7,40}\\b", "§")
                .replaceAll("[0-9]+", "#")
                .replaceAll("\\s+", " ")
                .strip();
        return Integer.toHexString(shape.hashCode());
    }
}
