package io.quarkus.prreview.classification.provenance.who;

import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Who;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;
import io.quarkus.prreview.clients.gh.model.GhCommit;
import io.quarkus.prreview.report.PlainText;

/**
 * Who each person of a PR is to the repository. The first matching rule wins and later lookups are not made. A
 * lookup that fails makes the person {@link Who#OTHER} with the failure as the reason, never a more or less trusted
 * label.
 */
@ApplicationScoped
public class WhoRules {

    /** How GitHub shows a deleted account. */
    public static final String GHOST = "ghost";

    private final GhClient gh;

    public WhoRules(GhClient gh) {
        this.gh = gh;
    }

    /**
     * The PR author: deleted, bot, then GitHub's {@code author_association} for the PR, read now (team for
     * {@code MEMBER} or {@code OWNER}, first-time, community, or other with the reason). A collaborator is first-time
     * or community by whether they have a commit reachable from the PR's base.
     */
    public Person prAuthor(int pr, GhAuthorship authorship) {
        String login = authorship.login();
        if (login == null || login.equals(GHOST)) {
            return Person.other(GHOST, "deleted account");
        }
        if (authorship.isBot()) {
            return Person.of(login, Who.BOT);
        }
        String association;
        try {
            association = gh.association(pr);
        } catch (GhException e) {
            return Person.other(login, "association lookup failed: " + PlainText.oneLine(e.getMessage()));
        }
        return switch (association) {
            case "MEMBER", "OWNER" -> Person.of(login, Who.TEAM);
            case "FIRST_TIME_CONTRIBUTOR", "FIRST_TIMER" -> Person.of(login, Who.FIRST_TIME);
            case "CONTRIBUTOR" -> Person.of(login, Who.COMMUNITY);
            // An outside collaborator has repository access without org membership: never team.
            case "COLLABORATOR" -> byCommitsBefore(login, authorship.baseSha());
            case "NONE" -> Person.other(login, "no association");
            case "MANNEQUIN" -> Person.other(login, "placeholder account");
            default -> Person.other(login, "unknown association " + association);
        };
    }

    /**
     * A commit's git author: a bot by the shape of its login or name ({@code app/...} or {@code ...[bot]}), since
     * GitHub reports no bot flag for commit authors; then team when a member of the org; then community or
     * first-time by whether they have a commit reachable from the PR's base. A 404 on membership is "not a member",
     * not a failure.
     */
    public Person commitAuthor(GhCommit.Author author, String baseSha) {
        String login = author.login();
        if (isBotShaped(author)) {
            return Person.of(login, Who.BOT);
        }
        boolean member;
        try {
            member = gh.isMember(login);
        } catch (GhException e) {
            return Person.other(login, "membership lookup failed: " + PlainText.oneLine(e.getMessage()));
        }
        return member ? Person.of(login, Who.TEAM) : byCommitsBefore(login, baseSha);
    }

    /**
     * A commit author already known not to be a member of the org: community or first-time by the PR's base, with no
     * second membership call.
     */
    public Person nonMemberCommitAuthor(GhCommit.Author author, String baseSha) {
        return byCommitsBefore(author.login(), baseSha);
    }

    /** Community when the person has a commit reachable from the PR's base, first-time when not. */
    private Person byCommitsBefore(String login, String baseSha) {
        try {
            return Person.of(login, gh.hasCommitsBefore(login, baseSha) ? Who.COMMUNITY : Who.FIRST_TIME);
        } catch (GhException e) {
            return Person.other(login, "commit history lookup failed: " + PlainText.oneLine(e.getMessage()));
        }
    }

    /** Whether a commit author's login or name has a bot account's shape. */
    public static boolean isBotShaped(GhCommit.Author author) {
        String login = author.login() == null ? "" : author.login();
        String name = author.name() == null ? "" : author.name();
        return login.startsWith("app/") || login.endsWith("[bot]") || name.endsWith("[bot]");
    }

    /** The same account arrives as {@code dependabot}, {@code dependabot[bot]} or {@code app/dependabot}. */
    public static String account(String login) {
        String normalized = login.toLowerCase(Locale.ROOT).strip();
        if (normalized.startsWith("app/")) {
            normalized = normalized.substring("app/".length());
        }
        if (normalized.endsWith("[bot]")) {
            normalized = normalized.substring(0, normalized.length() - "[bot]".length());
        }
        return normalized;
    }

}
