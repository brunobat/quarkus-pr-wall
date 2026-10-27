package io.quarkus.prreview.review.model;

/**
 * The one line a report says about who opened a PR (authorship-provenance design §4.6). Distinct from
 * {@link Provenance}, which is about reproducing <em>the review</em>; this is about the <em>PR</em>.
 *
 * <p>It carries only attested things: the login, whether the project's own {@code COMMITTERS.adoc}
 * lists that login, GitHub's account-type classification, and whether the author left an AI-assistance
 * marker. There is deliberately no "assessment" field and no tool name — the renderer states that
 * assistance was declared and stops there.
 *
 * <p>Critically, the absence of a declaration renders as nothing at all. Printing "no tooling declared"
 * would read as a clean bill of health the agent has no basis to issue: almost every AI-assisted PR
 * carries no marker, so silence on this axis is the normal case and means nothing.
 *
 * @param hasHumanCommitAuthor at least one commit carries a resolved, non-bot author — the one case
 *                             the account type gets wrong, since a bot-delivered PR can still be a
 *                             person's work (and an agent's PR can carry none)
 */
public record AuthorContext(
        String login,
        boolean isCommitter,
        boolean authorIsBot,
        boolean authorIsAllowlistedBot,
        boolean declaresAiAssistance,
        boolean hasHumanCommitAuthor) {

    /** True when there is anything worth printing. A bare login is already visible on the PR itself. */
    public boolean hasAnythingToSay() {
        return login != null && !login.isBlank()
                && (isCommitter || authorIsBot || declaresAiAssistance);
    }

    /** The four-way bucket, derived from the attested fields only. Never null. */
    public AuthorKind kind() {
        return AuthorKind.of(authorIsBot, authorIsAllowlistedBot, declaresAiAssistance);
    }
}
