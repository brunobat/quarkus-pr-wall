package io.quarkus.prreview.classification.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.github.GitHubFactsService;
import io.quarkus.prreview.classification.model.SourceResult;
import io.quarkus.prreview.classification.provenance.model.AiLabel;
import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Provenance;
import io.quarkus.prreview.classification.provenance.model.Who;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;
import io.quarkus.prreview.clients.gh.model.GhCommit;

class ProvenanceServiceTest {

    static final AiService AI = new AiService(new SignalList("test", List.of("co-authored-by: claude")),
            new SignalList("test", List.of("dependabot")));

    final GhClient gh = mock(GhClient.class);
    final ProvenanceService source = new ProvenanceService(gh, new GitHubFactsService(gh), new WhoService(gh), new LoginMemoService(),
            AI, 10);

    @Test
    void labelsThePrAuthorAndStampsTheReadTime() {
        when(gh.authorship(7)).thenReturn(new GhAuthorship("alice", "Alice", false, "", "abc", List.of()));
        when(gh.association(7)).thenReturn("MEMBER");
        Instant before = Instant.now();

        SourceResult<Provenance> result = source.gather(7);

        Provenance provenance = ((SourceResult.Available<Provenance>) result).facts();
        assertEquals(Person.of("alice", Who.TEAM), provenance.author());
        assertEquals(List.of(), provenance.commitAuthors());
        assertEquals(AiLabel.NONE, provenance.ai());
        assertTrue(!provenance.readAt().isBefore(before), provenance.readAt().toString());
    }

    @Test
    void anAuthorshipThatCannotBeReadMakesTheSourceUnavailable() {
        when(gh.authorship(7)).thenThrow(new GhException("no pull requests\nfound"));

        assertEquals(SourceResult.unavailable("gh: no pull requests found"), source.gather(7));
    }

    @Test
    void aFailedAssociationLabelsOnlyTheAuthor() {
        when(gh.authorship(7)).thenReturn(new GhAuthorship("alice", "Alice", false, "", "abc", List.of()));
        when(gh.association(7)).thenThrow(new GhException("timed out"));

        Provenance provenance = ((SourceResult.Available<Provenance>) source.gather(7)).facts();
        assertEquals(Person.other("alice", "association lookup failed: timed out"), provenance.author());
    }

    static GhCommit commit(String sha, GhCommit.Author... authors) {
        return new GhCommit(sha, "headline", "", List.of(authors));
    }

    static GhCommit.Author author(String login) {
        return new GhCommit.Author(login, login == null ? "Someone" : login);
    }

    @Test
    void onlyEachCommitsGitAuthorIsASubjectNeverATrailerCoAuthor() {
        GhAuthorship authorship = new GhAuthorship("pr-author", "", false, "", "abc", List.of(
                commit("1", author("alice"), author("bob")),
                commit("2", author(null)),
                commit("3", author("PR-Author")),
                commit("4", author("Alice")),
                commit("5"),
                commit("6", author("carol"), author("alice"))));

        assertEquals(List.of("alice", "carol"), ProvenanceService.commitAuthorSubjects(authorship).stream()
                .map(GhCommit.Author::login).toList());
    }

    @Test
    void aBotShapedCommitAuthorIsABotWithoutALookup() {
        when(gh.authorship(7)).thenReturn(new GhAuthorship("alice", "Alice", false, "", "abc", List.of(
                commit("1", new GhCommit.Author("copilot-swe-agent[bot]", "Copilot")),
                commit("2", new GhCommit.Author("app/some-app", "x")),
                commit("3", new GhCommit.Author("renamed", "dependabot[bot]")))));
        when(gh.association(7)).thenReturn("MEMBER");

        Provenance provenance = ((SourceResult.Available<Provenance>) source.gather(7)).facts();

        assertEquals(List.of(Person.of("copilot-swe-agent[bot]", Who.BOT), Person.of("app/some-app", Who.BOT),
                Person.of("renamed", Who.BOT)), provenance.commitAuthors());
        verify(gh).authorship(7);
        verify(gh).association(7);
        verifyNoMoreInteractions(gh);
    }

    @Test
    void thePrAuthorsOwnCommitsListNoOne() {
        when(gh.authorship(7)).thenReturn(new GhAuthorship("alice", "Alice", false, "", "abc",
                List.of(commit("1", author("alice")), commit("2", author("alice"), author("bob")))));
        when(gh.association(7)).thenReturn("MEMBER");

        assertEquals(List.of(), ((SourceResult.Available<Provenance>) source.gather(7)).facts().commitAuthors());
    }

    @Test
    void aCommitAuthorAlreadyLabelledInThisRunIsNotLookedUpAgain() {
        LoginMemoService memo = new LoginMemoService();
        ProvenanceService withMemo = new ProvenanceService(gh, new GitHubFactsService(gh), new WhoService(gh), memo, AI, 10);
        for (int pr : new int[] { 7, 8 }) {
            when(gh.authorship(pr)).thenReturn(new GhAuthorship("pr-author", "", false, "", "abc",
                    List.of(commit("1", author("alice")))));
            when(gh.association(pr)).thenReturn("CONTRIBUTOR");
        }
        when(gh.isMember("alice")).thenReturn(true);

        Provenance first = ((SourceResult.Available<Provenance>) withMemo.gather(7)).facts();
        Provenance second = ((SourceResult.Available<Provenance>) withMemo.gather(8)).facts();

        assertEquals(List.of(Person.of("alice", Who.TEAM)), first.commitAuthors());
        assertEquals(first.commitAuthors(), second.commitAuthors());
        verify(gh, times(1)).isMember("alice");

        memo.clear();
        withMemo.gather(8);
        verify(gh, times(2)).isMember("alice");
    }

    /** Two PRs whose commits are by {@code login}, on different bases, as open PRs almost always are. */
    ProvenanceService twoPrsOnDifferentBases(String login) {
        when(gh.authorship(7)).thenReturn(new GhAuthorship("pr-author", "", false, "", "abc",
                List.of(commit("1", author(login)))));
        when(gh.authorship(8)).thenReturn(new GhAuthorship("pr-author", "", false, "", "def",
                List.of(commit("2", author(login)))));
        when(gh.association(7)).thenReturn("CONTRIBUTOR");
        when(gh.association(8)).thenReturn("CONTRIBUTOR");
        return new ProvenanceService(gh, new GitHubFactsService(gh), new WhoService(gh), new LoginMemoService(), AI, 10);
    }

    List<Person> commitAuthors(ProvenanceService source, int pr) {
        return ((SourceResult.Available<Provenance>) source.gather(pr)).facts().commitAuthors();
    }

    @Test
    void aTeamOrCommunityPersonIsLookedUpOncePerRunWhateverEachPrsBase() {
        ProvenanceService source = twoPrsOnDifferentBases("alice");
        when(gh.isMember("alice")).thenReturn(false);
        when(gh.hasCommitsBefore("alice", "abc")).thenReturn(true);

        assertEquals(List.of(Person.of("alice", Who.COMMUNITY)), commitAuthors(source, 7));
        assertEquals(List.of(Person.of("alice", Who.COMMUNITY)), commitAuthors(source, 8));
        verify(gh, times(1)).isMember("alice");
        verify(gh, times(1)).hasCommitsBefore("alice", "abc");
        verify(gh, never()).hasCommitsBefore("alice", "def");
    }

    @Test
    void aFirstTimePersonIsCheckedAgainForAnotherBaseWhereTheirWorkMayHaveLanded() {
        ProvenanceService source = twoPrsOnDifferentBases("newbie");
        when(gh.isMember("newbie")).thenReturn(false);
        when(gh.hasCommitsBefore("newbie", "abc")).thenReturn(false);
        when(gh.hasCommitsBefore("newbie", "def")).thenReturn(true);

        assertEquals(List.of(Person.of("newbie", Who.FIRST_TIME)), commitAuthors(source, 7));
        assertEquals(List.of(Person.of("newbie", Who.COMMUNITY)), commitAuthors(source, 8));
        verify(gh, times(1)).hasCommitsBefore("newbie", "abc");
        verify(gh, times(1)).hasCommitsBefore("newbie", "def");
        verify(gh, times(1)).isMember("newbie");
    }

    @Test
    void aFailedLookupIsTriedAgainInTheNextPr() {
        ProvenanceService source = twoPrsOnDifferentBases("alice");
        when(gh.isMember("alice")).thenThrow(new GhException("timed out")).thenReturn(true);

        assertEquals(List.of(Person.other("alice", "membership lookup failed: timed out")), commitAuthors(source, 7));
        assertEquals(List.of(Person.of("alice", Who.TEAM)), commitAuthors(source, 8));
        verify(gh, times(2)).isMember("alice");
    }

    @Test
    void aFailedHistoryLookupIsTriedAgainWithoutAskingForMembershipAgain() {
        ProvenanceService source = twoPrsOnDifferentBases("alice");
        when(gh.isMember("alice")).thenReturn(false);
        when(gh.hasCommitsBefore("alice", "abc")).thenThrow(new GhException("HTTP 502"));
        when(gh.hasCommitsBefore("alice", "def")).thenReturn(true);

        assertEquals(List.of(Person.other("alice", "commit history lookup failed: HTTP 502")), commitAuthors(source, 7));
        assertEquals(List.of(Person.of("alice", Who.COMMUNITY)), commitAuthors(source, 8));
        verify(gh, times(1)).isMember("alice");
        verify(gh, times(1)).hasCommitsBefore("alice", "def");
    }

    @Test
    void aBotPrAuthorIsNotListedAgainUnderItsCommitSpelling() {
        when(gh.authorship(7)).thenReturn(new GhAuthorship("app/dependabot", "", true, "", "abc",
                List.of(commit("1", author("dependabot[bot]")), commit("2", author("Dependabot")))));

        assertEquals(List.of(), commitAuthors(source, 7));
    }

    @Test
    void commitAuthorsPastTheLimitAreNotLookedUp() {
        List<GhCommit> commits = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            commits.add(commit(String.valueOf(i), author("person" + i)));
        }
        commits.add(1, commit("bot", author("dependabot[bot]")));
        when(gh.authorship(7)).thenReturn(new GhAuthorship("pr-author", "", false, "", "abc", commits));
        when(gh.association(7)).thenReturn("CONTRIBUTOR");
        when(gh.isMember(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        Provenance provenance = ((SourceResult.Available<Provenance>) new ProvenanceService(gh, new GitHubFactsService(gh),
                new WhoService(gh),
                new LoginMemoService(), AI, 3).gather(7)).facts();

        assertEquals(List.of(Person.of("person1", Who.TEAM), Person.of("dependabot[bot]", Who.BOT),
                Person.of("person2", Who.TEAM), Person.of("person3", Who.TEAM),
                Person.other("person4", "lookup limit")), provenance.commitAuthors());
        verify(gh, never()).isMember("person4");
    }
}
