package io.quarkus.prreview.clients.gh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.provenance.WhoService;
import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Who;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;
import io.quarkus.prreview.clients.gh.model.GhCommit;
import tools.jackson.databind.json.JsonMapper;

/**
 * What GitHub really answers for the calls that label who wrote a PR, read with the installed {@code gh} against
 * {@code quarkusio/quarkus}. Read-only GETs; runs only with {@code -Plive} and a working {@code gh} login.
 */
@Tag("live")
class GhClientLiveTest {

    /** A PR opened by a member of the quarkusio org, with one commit by its author. */
    static final int MEMBER_PR = 58115;
    /** A PR with commits whose {@code Co-authored-by:} trailer GitHub matched to an account. */
    static final int CO_AUTHORED_PR = 57350;
    /** GitHub's mascot account, not a member of the quarkusio org. */
    static final String NON_MEMBER = "octocat";

    final GhClient gh = new GhClient(GhClientTest.config("gh", Duration.ofSeconds(60)), JsonMapper.builder().build());

    @Test
    void aMembersPrIsLabelledTeam() {
        GhAuthorship authorship = gh.authorship(MEMBER_PR);

        assertEquals("MEMBER", gh.association(MEMBER_PR));
        assertEquals(Person.of(authorship.login(), Who.TEAM), new WhoService(gh).prAuthor(MEMBER_PR, authorship));
    }

    @Test
    void membershipIsAnsweredThroughTheCacheWithA404ForANonMember() {
        String member = gh.authorship(MEMBER_PR).login();

        // Each call passes --cache, so the second answer may come from gh's cache; both must agree.
        assertTrue(gh.isMember(member));
        assertTrue(gh.isMember(member));
        assertFalse(gh.isMember(NON_MEMBER));
    }

    @Test
    void commitsBeforeABaseAreFound() {
        GhAuthorship authorship = gh.authorship(MEMBER_PR);

        assertTrue(gh.hasCommitsBefore(authorship.login(), authorship.baseSha()));
    }

    @Test
    void theBulkReadReturnsAuthorsAndCommitsWithTrailerCoAuthorsAfterTheGitAuthor() {
        Map<Integer, GhAuthorship> authorships = gh.authorships(List.of(MEMBER_PR, CO_AUTHORED_PR));

        assertEquals(2, authorships.size(), authorships.keySet().toString());
        GhAuthorship coAuthored = authorships.get(CO_AUTHORED_PR);
        assertFalse(coAuthored.commits().isEmpty());
        List<GhCommit> withTrailer = coAuthored.commits().stream()
                .filter(c -> c.body().toLowerCase().contains("co-authored-by:")).toList();
        assertFalse(withTrailer.isEmpty(), "PR " + CO_AUTHORED_PR + " has a commit with a Co-authored-by trailer");
        for (GhCommit commit : withTrailer) {
            assertTrue(commit.authors().size() >= 2, commit.toString());
            assertEquals(coAuthored.login(), commit.authors().getFirst().login(), commit.toString());
        }
    }
}
