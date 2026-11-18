package io.quarkus.prreview.clients.gh.model;

/**
 * One PR conversation comment, as {@code gh pr view --json comments} returns it: the latest body of an edited
 * comment.
 *
 * @param authorLogin the author's login, e.g. {@code quarkus-bot}; {@code null} for a deleted account
 * @param body the comment's markdown
 * @param createdAt when the comment was created, ISO-8601
 */
public record GhComment(String authorLogin, String body, String createdAt) {
}
