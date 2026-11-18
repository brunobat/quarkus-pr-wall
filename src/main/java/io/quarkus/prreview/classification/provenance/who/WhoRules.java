package io.quarkus.prreview.classification.provenance.who;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Who;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;

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
     * {@code MEMBER} or {@code OWNER}, first-time, community, or other with the reason).
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
            return Person.other(login, "association lookup failed: " + oneLine(e.getMessage()));
        }
        return switch (association) {
            case "MEMBER", "OWNER" -> Person.of(login, Who.TEAM);
            case "FIRST_TIME_CONTRIBUTOR", "FIRST_TIMER" -> Person.of(login, Who.FIRST_TIME);
            case "CONTRIBUTOR" -> Person.of(login, Who.COMMUNITY);
            case "COLLABORATOR" -> Person.other(login, "collaborator check not yet available");
            case "NONE" -> Person.other(login, "no association");
            case "MANNEQUIN" -> Person.other(login, "placeholder account");
            default -> Person.other(login, "unknown association " + association);
        };
    }

    static String oneLine(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }
}
