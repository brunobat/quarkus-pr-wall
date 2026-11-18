package io.quarkus.prreview.clients.gh.model;

import java.util.List;

/**
 * One commit of a PR, as {@code gh pr view --json commits} returns it.
 *
 * @param oid the commit's full SHA
 * @param headline the first line of the commit message
 * @param body the rest of the commit message; empty when there is none
 * @param authors GitHub's authors of the commit, in its order: the git author first, then every
 *        {@code Co-authored-by:} trailer GitHub matched to an account; e-mail addresses are not kept
 */
public record GhCommit(String oid, String headline, String body, List<Author> authors) {

    /**
     * @param login the account's login, or {@code null} when GitHub matched no account
     * @param name the name as written in the commit or the trailer
     */
    public record Author(String login, String name) {
    }
}
