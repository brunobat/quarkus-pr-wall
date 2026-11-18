package io.quarkus.prreview.clients.gh.model;

import java.util.List;

/**
 * Who wrote a PR, as {@code gh pr view --json author,body,baseRefOid,commits} reports it.
 *
 * @param login the PR author's login, or {@code null} when GitHub reports no author (a deleted account)
 * @param name the PR author's display name, or {@code null}
 * @param isBot whether GitHub marks the PR author as a bot
 * @param body the PR description; empty when there is none
 * @param baseSha the full SHA of the PR's base commit
 * @param commits the PR's commits, in GitHub's order
 */
public record GhAuthorship(String login, String name, boolean isBot, String body, String baseSha,
        List<GhCommit> commits) {
}
