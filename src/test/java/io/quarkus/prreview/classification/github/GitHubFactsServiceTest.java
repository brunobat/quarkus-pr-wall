package io.quarkus.prreview.classification.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.github.ci.CiService;
import io.quarkus.prreview.classification.github.size.SizeService;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;
import io.quarkus.prreview.clients.gh.model.GhCheck;
import io.quarkus.prreview.clients.gh.model.GhChecks;
import io.quarkus.prreview.clients.gh.model.GhComment;
import io.quarkus.prreview.clients.gh.model.GhCommit;
import io.quarkus.prreview.clients.gh.model.GhSize;

class GitHubFactsServiceTest {

    static final GhCheck FAILED = new GhCheck(GhCheck.CHECK_RUN, "JVM Tests", "Quarkus CI", "COMPLETED", "FAILURE",
            "https://github.com/q/q/actions/runs/1/job/2", null, null, null);
    static final GhChecks CHECKS = new GhChecks("abc", List.of(FAILED));
    static final List<GhComment> COMMENTS = List.of(new GhComment("someone", "hi", "2026-01-01T00:00:00Z"));
    static final GhSize SIZE = new GhSize(12, 3, 2);

    final GhClient gh = mock(GhClient.class);
    final GitHubFactsService facts = new GitHubFactsService(gh);
    final SizeService size = new SizeService(gh, facts);
    final CiService ci = new CiService(gh, facts);

    /** What the sources return with no bulk results at all: today's per-PR calls only. */
    List<Object> perPr(int pr) {
        GhClient perPr = mock(GhClient.class);
        when(perPr.size(pr)).thenReturn(SIZE);
        when(perPr.checks(pr)).thenReturn(CHECKS);
        when(perPr.comments(pr)).thenReturn(COMMENTS);
        GitHubFactsService none = new GitHubFactsService(perPr);
        return List.of(new SizeService(perPr, none).gather(pr), new CiService(perPr, none).gather(pr));
    }

    List<Object> gather(int pr) {
        return List.of(size.gather(pr), ci.gather(pr));
    }

    @Test
    void completeBulkResultsNeedNoPerPrCall() {
        when(gh.sizes(List.of(1))).thenReturn(Map.of(1, SIZE));
        when(gh.checks(List.of(1))).thenReturn(Map.of(1, CHECKS));
        when(gh.comments(List.of(1))).thenReturn(Map.of(1, COMMENTS));

        facts.prefetch(List.of(1));

        assertEquals(List.of(perPr(1)), List.of(gather(1)), "same result as per-PR calls");
        verify(gh, never()).size(anyInt());
        verify(gh, never()).checks(anyInt());
        verify(gh, never()).comments(anyInt());
    }

    @Test
    void aPrMissingFromTheBulkResultsIsReadOnItsOwn() {
        when(gh.sizes(List.of(1))).thenReturn(Map.of());
        when(gh.checks(List.of(1))).thenReturn(Map.of());
        stubPerPr(1);

        facts.prefetch(List.of(1));

        assertEquals(List.of(perPr(1)), List.of(gather(1)));
        verify(gh).size(1);
        verify(gh).checks(1);
        verify(gh).comments(1);
    }

    @Test
    void aFailedBulkCallLeavesOnlyItsOwnFactToPerPrCalls() {
        when(gh.sizes(List.of(1))).thenThrow(new GhException("HTTP 502"));
        when(gh.checks(List.of(1))).thenReturn(Map.of(1, CHECKS));
        when(gh.comments(List.of(1))).thenThrow(new GhException("HTTP 502"));
        stubPerPr(1);

        facts.prefetch(List.of(1));

        assertEquals(List.of(perPr(1)), List.of(gather(1)));
        verify(gh).size(1);
        verify(gh, never()).checks(anyInt());
        verify(gh).comments(1);
    }

    @Test
    void exactly100ChecksOrCommentsMayBeCutOffSoAreReadAgain() {
        GhChecks hundred = new GhChecks("abc", Collections.nCopies(100, FAILED));
        List<GhComment> hundredComments = Collections.nCopies(100, COMMENTS.getFirst());
        when(gh.sizes(List.of(1, 2))).thenReturn(Map.of(1, SIZE, 2, SIZE));
        when(gh.checks(List.of(1, 2))).thenReturn(Map.of(1, hundred, 2, CHECKS));
        when(gh.comments(List.of(1, 2))).thenReturn(Map.of(1, COMMENTS, 2, hundredComments));
        stubPerPr(1);
        stubPerPr(2);

        facts.prefetch(List.of(1, 2));

        assertEquals(List.of(perPr(1), perPr(2)), List.of(gather(1), gather(2)));
        verify(gh).checks(1);
        verify(gh, never()).checks(2);
        verify(gh).comments(2);
    }

    @Test
    void anUnexpectedErrorInABulkReadIsHandledLikeAFailedBulkCall() {
        when(gh.sizes(List.of(1))).thenThrow(new NullPointerException("bug"));
        when(gh.checks(List.of(1))).thenReturn(Map.of(1, CHECKS));
        when(gh.comments(List.of(1))).thenReturn(Map.of(1, COMMENTS));
        stubPerPr(1);

        facts.prefetch(List.of(1));

        assertEquals(List.of(perPr(1)), List.of(gather(1)));
        verify(gh).size(1);
        verify(gh, never()).checks(anyInt());
    }

    @Test
    void aFailingFallbackMakesTheFactUnavailable() {
        when(gh.sizes(List.of(1))).thenReturn(Map.of());
        when(gh.checks(List.of(1))).thenReturn(Map.of());
        when(gh.size(1)).thenThrow(new GhException("HTTP 403: forbidden"));
        when(gh.checks(1)).thenThrow(new GhException("HTTP 403: forbidden"));

        facts.prefetch(List.of(1));

        assertEquals("Unavailable[reason=gh: HTTP 403: forbidden]", size.gather(1).toString());
        assertEquals("Unavailable[reason=gh: HTTP 403: forbidden]", ci.gather(1).toString());
    }

    @Test
    void commentsAreReadInBulkForEveryPrWhateverItsChecksSay() {
        GhCheck passed = new GhCheck(GhCheck.CHECK_RUN, "Build", "Quarkus CI", "COMPLETED", "SUCCESS", "", null, null,
                null);
        when(gh.sizes(List.of(1, 2, 3))).thenReturn(Map.of());
        when(gh.checks(List.of(1, 2, 3))).thenReturn(Map.of(1, CHECKS, 2, new GhChecks("def", List.of(passed))));
        when(gh.comments(List.of(1, 2, 3))).thenReturn(Map.of(1, COMMENTS, 2, COMMENTS));
        when(gh.comments(3)).thenReturn(COMMENTS);

        facts.prefetch(List.of(1, 2, 3));

        verify(gh).comments(List.of(1, 2, 3));
        assertEquals(COMMENTS, facts.comments(1).orElseThrow());
        assertEquals(COMMENTS, facts.comments(2).orElseThrow());
        // A PR the bulk read missed is read on its own when its CI is gathered.
        assertTrue(facts.comments(3).isEmpty());
        when(gh.checks(3)).thenReturn(new GhChecks("ghi", List.of(passed)));
        ci.gather(3);
        verify(gh).comments(3);
        verify(gh, never()).comments(2);
    }

    static GhAuthorship authorship(int commits) {
        return new GhAuthorship("alice", "Alice", false, "", "abc", Collections.nCopies(commits,
                new GhCommit("1", "h", "", List.of(new GhCommit.Author("alice", "Alice")))));
    }

    @Test
    void whoWroteAPrComesFromTheBulkReadUnlessItIsMissingFailedOrMayBeCutOff() {
        when(gh.authorships(List.of(1, 2, 3))).thenReturn(Map.of(1, authorship(99), 2, authorship(100)));

        facts.prefetch(List.of(1, 2, 3));

        assertEquals(authorship(99), facts.authorship(1).orElseThrow());
        assertTrue(facts.authorship(2).isEmpty(), "exactly 100 commits may have been cut off");
        assertTrue(facts.authorship(3).isEmpty(), "missing from the results");

        when(gh.authorships(List.of(1))).thenThrow(new GhException("HTTP 502"));
        facts.prefetch(List.of(1));
        assertTrue(facts.authorship(1).isEmpty(), "the bulk call failed");
    }

    void stubPerPr(int pr) {
        when(gh.size(pr)).thenReturn(SIZE);
        when(gh.checks(pr)).thenReturn(CHECKS);
        when(gh.comments(pr)).thenReturn(COMMENTS);
    }
}
