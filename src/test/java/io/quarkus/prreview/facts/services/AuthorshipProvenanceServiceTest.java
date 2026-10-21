package io.quarkus.prreview.facts.services;

import io.quarkus.prreview.facts.model.AuthorshipProvenance;
import io.quarkus.prreview.facts.model.ChangedFile;
import io.quarkus.prreview.facts.model.CommitMeta;
import io.quarkus.prreview.facts.model.FileTag;
import io.quarkus.prreview.facts.model.PullRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic provenance facts (authorship-provenance design §4.1, phase 1).
 *
 * <p>The thing worth stating plainly: every assertion below is about what a PR <em>declares</em>. None
 * of them is about whether a human wrote the code, because no signal available to this agent answers
 * that. In particular {@link #noMarkerAnywhereMeansNothingWasDeclaredNotThatNoToolWasUsed()} pins the
 * asymmetry — absence of a marker is not evidence of absence of tooling, and the fact must not be read
 * as if it were.
 */
@QuarkusTest
class AuthorshipProvenanceServiceTest {

    @Inject
    AuthorshipProvenanceService service;

    private static CommitMeta commit(String body, String authorLogin, String committerName) {
        return new CommitMeta("abc1234", "Fix the thing", body, "2026-10-01T10:00:00Z",
                "2026-10-01T10:00:00Z", 1, "Alice", authorLogin, committerName, "");
    }

    private static PullRequest pr(String body, boolean bot, String login, List<CommitMeta> commits) {
        return new PullRequest(1, "Fix the thing", body, List.of(), login, bot, "main", "fix",
                "base", "head", "", commits, "");
    }

    private AuthorshipProvenance resolve(PullRequest pr) {
        return service.resolve(pr, List.of());
    }

    @Test
    void aCommitTrailerIsADeclaration() {
        AuthorshipProvenance p = resolve(pr("", false, "alice",
                List.of(commit("Co-Authored-By: Claude <noreply@anthropic.com>", "alice", "Alice"))));

        assertTrue(p.declaresAiAssistance());
    }

    /**
     * The case the obvious implementation misses. On quarkusio/quarkus "Generated with Claude Code"
     * appears in 17 PR bodies and <em>zero</em> commits, so a commit-only check sees none of them.
     */
    @Test
    void aFooterInThePrBodyAloneIsAlsoADeclaration() {
        AuthorshipProvenance p = resolve(pr("Fixes the thing.\n\n🤖 Generated with Claude Code",
                false, "alice", List.of(commit("", "alice", "Alice"))));

        assertTrue(p.declaresAiAssistance(), "a body footer with no commit trailer must still count");
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(resolve(pr("", false, "alice",
                List.of(commit("co-authored-by: CLAUDE <x@y>", "alice", "Alice")))).declaresAiAssistance());
    }

    @Test
    void noMarkerAnywhereMeansNothingWasDeclaredNotThatNoToolWasUsed() {
        AuthorshipProvenance p = resolve(pr("A perfectly ordinary description.", false, "alice",
                List.of(commit("Signed-off-by: Alice <a@b>", "alice", "Alice"))));

        assertFalse(p.declaresAiAssistance());
    }

    @Test
    void anAllowlistedBotIsRecognisedWhateverSpellingItsLoginArrivesIn() {
        for (String login : List.of("dependabot", "dependabot[bot]", "app/dependabot", "Dependabot")) {
            AuthorshipProvenance p = resolve(pr("", true, login, List.of()));
            assertTrue(p.authorIsBot(), login);
            assertTrue(p.authorIsAllowlistedBot(), login);
        }
    }

    /** A coding agent opens substantive PRs; routing it to the "routine automation" bucket would skip review. */
    @Test
    void aCodingAgentIsABotButNotAnAllowlistedOne() {
        AuthorshipProvenance p = resolve(pr("", true, "app/copilot-swe-agent", List.of()));

        assertTrue(p.authorIsBot());
        assertFalse(p.authorIsAllowlistedBot());
    }

    @Test
    void aBotOpenedPrCanStillCarryHumanAuthoredCommits() {
        AuthorshipProvenance p = resolve(pr("", true, "dependabot",
                List.of(commit("", "dependabot[bot]", "GitHub"), commit("", "alice", "Alice"))));

        assertTrue(p.authorIsBot());
        assertTrue(p.hasHumanCommitAuthor());
    }

    @Test
    void anUnresolvedCommitAuthorIsNotCountedAsHuman() {
        AuthorshipProvenance p = resolve(pr("", true, "dependabot",
                List.of(commit("", "", "GitHub"))));

        assertFalse(p.hasHumanCommitAuthor());
    }

    @Test
    void addedLinesAreSplitDocsVersusCode() {
        List<ChangedFile> files = List.of(
                new ChangedFile("docs/src/main/asciidoc/foo.adoc", FileTag.DOCS, false, 120, 0, "docs", List.of(), false),
                new ChangedFile("core/src/main/java/Foo.java", FileTag.RUNTIME, true, 8, 2, "core", List.of(), false),
                new ChangedFile("core/src/test/java/FooTest.java", FileTag.TEST, false, 15, 0, "core", List.of(), false),
                new ChangedFile("pom.xml", FileTag.BUILD_CI_SECURITY, true, 3, 1, "root", List.of(), false));

        AuthorshipProvenance p = service.resolve(pr("", false, "alice", List.of()), files);

        assertEquals(120, p.docsAddedLines());
        assertEquals(23, p.codeAddedLines(), "code is runtime + test; build files count as neither");
    }

    @Test
    void tickedChecklistBoxesAreCountedAndUntickedOnesAreNot() {
        String body = """
                - [x] I ran the tests
                * [X] I updated the docs
                - [ ] I did not do this one
                + [x] nested marker styles count
                some [x] text that is not a list item""";

        assertEquals(3, AuthorshipProvenanceService.completedChecklistItems(body));
    }

    @Test
    void aPrWithNoCommitsResolvesToAnEmptyProvenanceRatherThanFailing() {
        AuthorshipProvenance p = resolve(pr(null, false, "alice", List.of()));

        assertFalse(p.declaresAiAssistance());
        assertFalse(p.hasHumanCommitAuthor());
        assertEquals(0, p.completedChecklistItems());
        assertTrue(p.commits().isEmpty());
    }
}
