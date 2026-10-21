package io.quarkus.prreview.facts.services;

import io.quarkus.prreview.facts.model.CommitMeta;
import io.quarkus.prreview.facts.model.PullRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bulk GraphQL fetch (authorship-provenance design §4.1, phase 0). This is a refactor of how PR
 * metadata arrives, not of what the facts layer concludes from it — the regression guard on <em>that</em>
 * is the eval suite, which runs nine recorded PRs through the whole chain against unchanged expectations.
 * What is tested here is the part the eval suite cannot see: the response shapes GitHub actually returns.
 */
@QuarkusTest
@TestProfile(FetchPrTest.RepoProfile.class)
class FetchPrTest {

    /** {@code gh api graphql} takes no {@code -R}, so the repo has to be configured for the query itself. */
    public static class RepoProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("gh.repo", "quarkusio/quarkus");
        }
    }

    private static final String ONE_PR = """
            {"data": {"pr10": {"pullRequest": {
              "number": 10, "title": "Fix the thing", "body": "closes #9",
              "baseRefName": "main", "headRefName": "fix-thing",
              "baseRefOid": "base10", "headRefOid": "head10",
              "author": {"__typename": "User", "login": "alice"},
              "labels": {"nodes": [{"name": "area/core"}, {"name": "kind/bug"}]},
              "closingIssuesReferences": {"nodes": [{"title": "Thing broken", "body": "details"}]},
              "commits": {"nodes": [{"commit": {
                "oid": "c1", "messageHeadline": "Fix the thing", "messageBody": "",
                "authoredDate": "2026-10-01T10:00:00Z", "committedDate": "2026-10-01T10:00:00Z",
                "parents": {"totalCount": 1},
                "author": {"name": "Alice", "user": {"login": "alice"}},
                "committer": {"name": "Alice", "user": {"login": "alice"}}
              }}]}
            }}}}""";

    private final List<String> calls = new ArrayList<>();

    @Inject
    FetchPr fetchPr;

    @AfterEach
    void clearFixture() {
        NoNetworkGhClient.clear();
        calls.clear();
        // Leave no prefetched state behind for the next test — the cache outlives a single fetch by design.
        fetchPr.prefetch(List.of());
    }

    /** Serve {@code api graphql} from {@code graphql} and every {@code pr diff} as a one-line patch. */
    private void install(String graphql) {
        NoNetworkGhClient.install(args -> {
            calls.add(String.join(" ", args[0], args[1]));
            if (args[0].equals("api") && args[1].equals("graphql")) {
                return graphql;
            }
            if (args[0].equals("pr") && args[1].equals("diff")) {
                return "diff --git a/A.java b/A.java\n";
            }
            throw new AssertionError("unexpected gh call: " + String.join(" ", args));
        });
    }

    @Test
    void aSinglePrFetchMapsEveryFieldTheOldPrViewCallSupplied() {
        install(ONE_PR);

        PullRequest pr = fetchPr.fetch(10);

        assertEquals(10, pr.number());
        assertEquals("Fix the thing", pr.title());
        assertEquals("closes #9", pr.body());
        assertEquals(List.of("area/core", "kind/bug"), pr.labels());
        assertEquals("alice", pr.authorLogin());
        assertFalse(pr.authorIsBot());
        assertEquals("main", pr.baseBranch());
        assertEquals("fix-thing", pr.headBranch());
        assertEquals("base10", pr.baseSha());
        assertEquals("head10", pr.headSha());
        assertEquals("Thing broken\ndetails\n", pr.linkedIssueText());
        assertEquals("diff --git a/A.java b/A.java\n", pr.rawDiff());
    }

    @Test
    void commitMetadataIsCarriedThrough() {
        install(ONE_PR);

        CommitMeta commit = fetchPr.fetch(10).commits().getFirst();

        assertEquals("c1", commit.oid());
        assertEquals("Fix the thing", commit.messageHeadline());
        assertEquals("2026-10-01T10:00:00Z", commit.authoredDate());
        assertEquals("Alice", commit.committerName());
        assertEquals("alice", commit.committerLogin());
        assertFalse(commit.isMergeCommit());
    }

    @Test
    void aPrefetchedSweepCostsOneGraphqlCallForTheWholeRange() {
        install("""
                {"data": {
                  "pr10": {"pullRequest": {"number": 10, "title": "A", "author": {"__typename": "User", "login": "alice"}}},
                  "pr11": {"pullRequest": {"number": 11, "title": "B", "author": {"__typename": "Bot", "login": "dependabot"}}}
                }}""");

        fetchPr.prefetch(List.of(10, 11));
        PullRequest first = fetchPr.fetch(10);
        PullRequest second = fetchPr.fetch(11);

        assertEquals("A", first.title());
        assertEquals("B", second.title());
        assertTrue(second.authorIsBot(), "GraphQL __typename Bot is the account type, straight through");
        assertEquals(List.of("api graphql", "pr diff", "pr diff"), calls,
                "the sweep must not re-query metadata it already holds");
    }

    /** GitHub answers a deleted or inaccessible PR with a null alias and an {@code errors[]} entry. */
    @Test
    void aNullAliasIsOnePrGoneNotAFailedSweep() {
        install("""
                {"data": {
                  "pr10": {"pullRequest": {"number": 10, "title": "A", "author": {"__typename": "User", "login": "alice"}}},
                  "pr20": {"pullRequest": null}
                }, "errors": [{"type": "NOT_FOUND", "message": "Could not resolve to a PullRequest with the number of 20."}]}""");

        fetchPr.prefetch(List.of(10, 20));

        assertEquals("A", fetchPr.fetch(10).title());
        GhClient.GhException gone = assertThrows(GhClient.GhException.class, () -> fetchPr.fetch(20));
        assertTrue(gone.getMessage().contains("#20"), gone.getMessage());
    }

    @Test
    void aPrWithNoVisibleCommitsIsNotAnError() {
        install("""
                {"data": {"pr10": {"pullRequest": {
                  "number": 10, "title": "A", "author": {"__typename": "User", "login": "alice"},
                  "labels": {"nodes": []}, "closingIssuesReferences": {"nodes": []},
                  "commits": {"nodes": []}
                }}}}""");

        PullRequest pr = fetchPr.fetch(10);

        assertTrue(pr.commits().isEmpty());
        assertTrue(pr.labels().isEmpty());
        assertEquals("", pr.linkedIssueText());
    }

    /** The bulk query is an optimisation; a review must still complete when it fails outright. */
    @Test
    void aFailedPrefetchFallsBackToPerPrQueries() {
        boolean[] prefetching = {true};
        NoNetworkGhClient.install(args -> {
            if (args[0].equals("api") && prefetching[0]) {
                prefetching[0] = false;
                throw new GhClient.GhException("gh failed (1): upstream hiccup");
            }
            return args[0].equals("api") ? ONE_PR : "";
        });

        fetchPr.prefetch(List.of(10));

        assertEquals("Fix the thing", fetchPr.fetch(10).title());
    }
}
