package io.quarkus.prreview.classification.provenance.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.provenance.model.AiLabel;
import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Who;
import io.quarkus.prreview.classification.provenance.signals.SignalList;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;
import io.quarkus.prreview.clients.gh.model.GhCommit;

/** Every case is about what a PR declares or which account opened it; nothing here judges the code. */
class AiServiceTest {

    static final SignalList SIGNALS = new SignalList("test", List.of("co-authored-by: claude",
            "generated with claude code"));
    static final SignalList AUTOMATION = new SignalList("test", List.of("dependabot", "quarkus-bot"));
    static final AiService RULES = new AiService(SIGNALS, AUTOMATION);
    static final Person ALICE = Person.of("alice", Who.COMMUNITY);

    static GhAuthorship pr(String body, GhCommit... commits) {
        return new GhAuthorship("alice", "Alice", false, body, "abc", List.of(commits));
    }

    static GhCommit commit(String sha, String headline, String body) {
        return new GhCommit(sha, headline, body, List.of(new GhCommit.Author("alice", "Alice")));
    }

    @Test
    void aCommitTrailerIsADeclaration() {
        GhAuthorship pr = pr("Fixes #1", commit("0123456789abcdef", "Fix it", "Co-Authored-By: Claude <noreply@x>"));

        assertEquals(new AiLabel(AiLabel.Kind.DECLARED, "commit 0123456: \"co-authored-by: claude\""),
                RULES.label(pr, ALICE, List.of()));
    }

    @Test
    void aFooterInThePrBodyAloneIsAlsoADeclaration() {
        GhAuthorship pr = pr("Does things.\n\n🤖 Generated with Claude Code", commit("1", "Fix it", ""));

        assertEquals(new AiLabel(AiLabel.Kind.DECLARED, "PR body: \"generated with claude code\""),
                RULES.label(pr, ALICE, List.of()));
    }

    @Test
    void matchingIsCaseInsensitiveAndWhitespaceRunsCollapse() {
        assertEquals(AiLabel.Kind.DECLARED, RULES.label(pr("GENERATED WITH CLAUDE CODE"), ALICE, List.of()).kind());
        assertEquals(AiLabel.Kind.DECLARED,
                RULES.label(pr("", commit("1", "x", "Co-Authored-By:  Claude")), ALICE, List.of()).kind());
        assertEquals(AiLabel.Kind.DECLARED,
                RULES.label(pr("Generated with\nClaude\tCode"), ALICE, List.of()).kind());
    }

    @Test
    void noMarkerAnywhereMeansNothingWasDeclared() {
        assertEquals(AiLabel.NONE, RULES.label(pr("Plain description", commit("1", "Fix", "Plain body")), ALICE,
                List.of()));
        assertEquals(AiLabel.NONE, RULES.label(pr(null), ALICE, List.of()));
    }

    @Test
    void routineAutomationIsNotAnAgentWhateverSpellingItsLoginArrivesIn() {
        for (String login : List.of("dependabot", "dependabot[bot]", "app/dependabot", "Quarkus-Bot")) {
            assertEquals(AiLabel.NONE, RULES.label(pr(""), Person.of(login, Who.BOT), List.of()), login);
        }
    }

    @Test
    void aCodingAgentOpeningThePrIsAnAgent() {
        assertEquals(new AiLabel(AiLabel.Kind.AGENT, "app/copilot-swe-agent"),
                RULES.label(pr("Generated with Claude Code"), Person.of("app/copilot-swe-agent", Who.BOT), List.of()));
    }

    @Test
    void anAgentAuthoredCommitInAHumanPrIsAnAgent() {
        assertEquals(new AiLabel(AiLabel.Kind.AGENT, "copilot-swe-agent[bot]"), RULES.label(pr(""), ALICE,
                List.of(Person.of("dependabot[bot]", Who.BOT), Person.of("copilot-swe-agent[bot]", Who.BOT))));
    }

    @Test
    void aTrailerFromAnUnlabelledCoAuthorStillDeclares() {
        GhCommit coAuthored = new GhCommit("abcdef0123", "Fix", "Co-authored-by: Claude <noreply@anthropic.com>",
                List.of(new GhCommit.Author("alice", "Alice"), new GhCommit.Author("claude", "Claude")));

        assertEquals(AiLabel.Kind.DECLARED, RULES.label(pr("", coAuthored), ALICE, List.of()).kind());
    }

    @Test
    void emptyListsNeverDeclareAndMakeEveryBotAnAgent() {
        AiService empty = new AiService(new SignalList("x", List.of()), new SignalList("x", List.of()));

        assertEquals(AiLabel.NONE, empty.label(pr("Generated with Claude Code"), ALICE, List.of()));
        assertEquals(new AiLabel(AiLabel.Kind.AGENT, "dependabot[bot]"),
                empty.label(pr(""), Person.of("dependabot[bot]", Who.BOT), List.of()));
    }
}
