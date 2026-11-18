package io.quarkus.prreview.clients.gh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.prreview.clients.gh.model.GhAuthorship;
import io.quarkus.prreview.clients.gh.model.GhCheck;
import io.quarkus.prreview.clients.gh.model.GhChecks;
import io.quarkus.prreview.clients.gh.model.GhComment;
import io.quarkus.prreview.clients.gh.model.GhCommit;
import io.quarkus.prreview.clients.gh.model.GhSize;
import io.quarkus.prreview.config.ReviewConfig;
import io.quarkus.prreview.it.support.FakeGh;
import io.quarkus.prreview.pr.model.PullRequest;
import tools.jackson.databind.json.JsonMapper;

/** Runs the real {@link GhClient} against the fake {@code gh} script in {@code src/test/resources/fake-gh}. */
class GhClientTest {

    static final Path FAKE_GH_DIR = Path.of("src/test/resources/fake-gh").toAbsolutePath();
    static final String FAKE_GH = FAKE_GH_DIR.resolve("gh").toString();

    private final GhClient client = new GhClient(config(FAKE_GH, Duration.ofSeconds(1)), JsonMapper.builder().build());

    @Test
    void fetchesMetadataAndDiff() {
        PullRequest pr = client.fetch(101);

        assertEquals(101, pr.number());
        assertEquals("Fix the frobnicator", pr.title());
        assertEquals("https://github.com/quarkusio/quarkus/pull/101", pr.url());
        assertEquals("OPEN", pr.state());
        assertTrue(pr.diff().startsWith("diff --git a/core/runtime/src/main/java/io/quarkus/Foo.java"), pr.diff());
    }

    @Test
    void unknownPrCarriesGhMessage() {
        GhException e = assertThrows(GhException.class, () -> client.fetch(404));
        assertTrue(e.getMessage().contains("no pull requests found"), e.getMessage());
        assertNull(e.pullRequest());
    }

    @Test
    void tooLargeDiffMessageIsPropagated() {
        GhException e = assertThrows(GhException.class, () -> client.fetch(406));
        assertTrue(e.getMessage().contains("HTTP 406"), e.getMessage());
        assertTrue(e.getMessage().contains("too_large"), e.getMessage());
        // gh pr view succeeded; only the diff failed, so the title is kept.
        assertEquals("Reformat everything", e.pullRequest().title());
        assertEquals("https://github.com/quarkusio/quarkus/pull/406", e.pullRequest().url());
    }

    @Test
    void fetchesSizeCounts() {
        assertEquals(new GhSize(12, 3, 2), client.size(101));
    }

    @Test
    void sizeCountsAreKnownWhenTheDiffIsRefused() {
        assertEquals(new GhSize(17, 19751, 1244), client.size(406));
    }

    @Test
    void aMissingCountIsAFailureNotZero() {
        GhException e = assertThrows(GhException.class, () -> client.size(102));
        assertTrue(e.getMessage().contains("did not report additions"), e.getMessage());
    }

    @Test
    void sizeOfAnUnknownPrIsAGhFailure() {
        GhException e = assertThrows(GhException.class, () -> client.size(404));
        assertTrue(e.getMessage().contains("no pull requests found"), e.getMessage());
    }

    @Test
    void fetchesTheHeadCommitAndEveryKindOfCheck() {
        GhChecks checks = client.checks(101);

        assertEquals("ba9690af0e8bdc5d1c0f7a6c2b6b1e7d3c9a4f21", checks.headRefOid());
        assertEquals(9, checks.rollup().size());
        GhCheck failed = checks.rollup().get(1);
        assertEquals(new GhCheck(GhCheck.CHECK_RUN, "JVM Integration Tests - JDK 17", "Quarkus CI", "COMPLETED",
                "FAILURE", "https://github.com/quarkusio/quarkus/actions/runs/39914638446/job/120285311801", null, null,
                null), failed);
        GhCheck running = checks.rollup().get(4);
        assertEquals("IN_PROGRESS", running.status());
        assertEquals("", running.conclusion());
        assertEquals(new GhCheck(GhCheck.STATUS_CONTEXT, null, null, null, null, null, "CI Merge Gate", "FAILURE", ""),
                checks.rollup().getLast());
    }

    @Test
    void noChecksIsAnEmptyListNotAFailure() {
        assertEquals(List.of(), client.checks(103).rollup());
    }

    @Test
    void aMissingRollupIsAFailure() {
        GhException e = assertThrows(GhException.class, () -> client.checks(105));
        assertTrue(e.getMessage().contains("did not report statusCheckRollup"), e.getMessage());
    }

    @Test
    void fetchesCommentsOldestFirstWithTheirAuthors() {
        List<GhComment> comments = client.comments(105);

        assertEquals(List.of("quarkus-bot", "a-human"), comments.stream().map(GhComment::authorLogin).toList());
        assertTrue(comments.getFirst().body().startsWith("# Status for workflow `Quarkus CI`"),
                comments.getFirst().body());
        assertEquals("2026-10-23T12:00:00Z", comments.getFirst().createdAt());
    }

    @Test
    void commentsThatCannotBeReadAreAGhFailure() {
        GhException e = assertThrows(GhException.class, () -> client.comments(404));
        assertTrue(e.getMessage().contains("no pull requests found"), e.getMessage());
    }

    @Test
    void fetchesWhoWroteThePr() {
        GhAuthorship authorship = client.authorship(101);

        assertEquals("alice", authorship.login());
        assertEquals("Alice", authorship.name());
        assertEquals(false, authorship.isBot());
        assertEquals("Fixes the frobnicator.", authorship.body());
        assertEquals(40, authorship.baseSha().length());
        assertEquals(1, authorship.commits().size());
        GhCommit commit = authorship.commits().getFirst();
        assertEquals("Fix the frobnicator", commit.headline());
        assertEquals("", commit.body());
        assertEquals(List.of(new GhCommit.Author("alice", "Alice")), commit.authors());
    }

    @Test
    void aDeletedAuthorAndAnUnmatchedCommitAuthorHaveNoLogin() {
        GhAuthorship authorship = client.authorship(102);

        assertNull(authorship.login());
        assertEquals(false, authorship.isBot());
        assertNull(authorship.commits().getFirst().authors().getFirst().login());
    }

    @Test
    void missingCommitsAreAFailure() {
        GhException e = assertThrows(GhException.class, () -> client.authorship(105));
        assertTrue(e.getMessage().contains("did not report baseRefOid and commits"), e.getMessage());
    }

    @Test
    void readsTheAuthorAssociation() {
        assertEquals("MEMBER", client.association(101));
        assertEquals("CONTRIBUTOR", client.association(103));
    }

    @Test
    void anAssociationThatCannotBeReadIsAGhFailure() {
        GhException e = assertThrows(GhException.class, () -> client.association(104));
        assertTrue(e.getMessage().contains("HTTP 502"), e.getMessage());
        assertThrows(GhException.class, () -> client.association(404));
    }

    @Test
    void membershipIsReadFromTheHttpStatus() {
        assertTrue(client.isMember("member"));
        assertFalse(client.isMember("nobody"));
        GhException e = assertThrows(GhException.class, () -> client.isMember("flaky"));
        assertEquals("HTTP 502", e.getMessage());
    }

    @Test
    void perPersonLookupsAreCachedButTheAssociationIsNot() throws IOException {
        client.isMember("member");
        client.hasCommitsBefore("regular", SHA);
        client.association(101);

        List<String> log = Files.readAllLines(FakeGh.CALLS_LOG);
        List<String> last = log.subList(log.size() - 3, log.size());
        assertEquals(List.of("api orgs/quarkusio/members/member -i --cache 3600s",
                "api repos/quarkusio/quarkus/commits?author=regular&sha=" + SHA + "&per_page=1 --cache 3600s",
                "api repos/quarkusio/quarkus/pulls/101 --jq .author_association"), last);
    }

    @Test
    void commitsBeforeTheBaseAreReadFromTheCommitsList() {
        assertTrue(client.hasCommitsBefore("regular", SHA));
        assertFalse(client.hasCommitsBefore("new", SHA));
        GhException e = assertThrows(GhException.class, () -> client.hasCommitsBefore("broken", SHA));
        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
    }

    @Test
    void anUnsafeLoginOrBaseIsALookupFailureBeforeAnyProcessStarts() {
        assertThrows(GhException.class, () -> client.isMember("a/b"));
        assertThrows(GhException.class, () -> client.isMember(null));
        assertThrows(GhException.class, () -> client.hasCommitsBefore("a?b", SHA));
        assertThrows(GhException.class, () -> client.hasCommitsBefore("regular", "main"));
    }

    @Test
    void slowGhTimesOut() throws IOException, InterruptedException {
        GhException e = assertThrows(GhException.class, () -> client.fetch(99));
        assertTrue(e.getMessage().contains("timed out after"), e.getMessage());

        // gh's own children are killed too: a child holding the output pipes would otherwise outlive the timeout.
        List<String> log = Files.readAllLines(FakeGh.CALLS_LOG);
        long sleepPid = Long.parseLong(log.reversed().stream().filter(l -> l.startsWith("sleep-pid "))
                .findFirst().orElseThrow().substring("sleep-pid ".length()));
        assertTrue(endsWithin(sleepPid, 2000), "gh's child process " + sleepPid + " is still running");
    }

    private static boolean endsWithin(long pid, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(50);
        }
        return true;
    }

    @Test
    void missingBinaryIsAGhFailure() {
        GhClient missing = new GhClient(config("/nonexistent/gh", Duration.ofSeconds(1)), JsonMapper.builder().build());
        GhException e = assertThrows(GhException.class, () -> missing.fetch(101));
        assertTrue(e.getMessage().contains("cannot run /nonexistent/gh"), e.getMessage());
    }

    @Test
    void onlyReadOnlySubcommandsAreAllowed() {
        assertEquals(List.of(FAKE_GH, "pr", "view", "1", "-R", "quarkusio/quarkus"),
                client.command(List.of("pr", "view", "1")));
        assertEquals(List.of(FAKE_GH, "pr", "list", "--search", "1 2", "-R", "quarkusio/quarkus"),
                client.command(List.of("pr", "list", "--search", "1 2")));
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("pr", "merge", "1")));
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("pr", "create")));
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("pr", "edit", "1")));
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("issue", "list")));
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("pr", "comment", "1")));
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("pr", "api")));
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("pr")));
    }

    private static final String SHA = "3cde8c894156969db35001fe9f26051c3c81fd22";

    @Test
    void apiGetsToTheThreeReadOnlyPathsRunWithoutRepoFlag() {
        assertEquals(List.of(FAKE_GH, "api", "repos/quarkusio/quarkus/pulls/58115", "--jq", ".author_association"),
                client.command(List.of("api", "repos/quarkusio/quarkus/pulls/58115", "--jq", ".author_association")));
        assertEquals(List.of(FAKE_GH, "api", "orgs/quarkusio/members/brunobat", "-i", "--cache", "3600s"),
                client.command(List.of("api", "orgs/quarkusio/members/brunobat", "-i", "--cache", "3600s")));
        String commits = "repos/quarkusio/quarkus/commits?author=some-one&sha=" + SHA + "&per_page=1";
        assertEquals(List.of(FAKE_GH, "api", commits, "--cache", "1h"),
                client.command(List.of("api", commits, "--cache", "1h")));
        assertEquals(List.of(FAKE_GH, "api", "orgs/quarkusio/members/dependabot[bot]"),
                client.command(List.of("api", "orgs/quarkusio/members/dependabot[bot]")));
    }

    @Test
    void apiFlagsThatCouldWriteOrSendABodyAreRefused() {
        String pulls = "repos/quarkusio/quarkus/pulls/1";
        for (List<String> flags : List.of(List.of("-X", "GET"), List.of("-XPOST"), List.of("--method", "GET"),
                List.of("-f", "a=b"), List.of("--raw-field", "a=b"), List.of("-F", "a=b"), List.of("--field", "a=b"),
                List.of("--input", "file"), List.of("-H", "Accept: x"), List.of("--paginate"), List.of("--cache"),
                List.of("--cache", "forever"), List.of("--jq"), List.of("--jq", "-X"), List.of("-R", "x/y"))) {
            List<String> args = new java.util.ArrayList<>(List.of("api", pulls));
            args.addAll(flags);
            assertThrows(IllegalArgumentException.class, () -> client.command(args), args.toString());
        }
    }

    @Test
    void apiPathsOutsideTheAllowedOnesAreRefused() {
        for (String path : List.of("graphql", "user", "search/issues?q=x", "repos/quarkusio/quarkus/contents/README.md",
                "repos/x/y/pulls/1", "repos/quarkusio/quarkus/pulls/1/merge", "repos/quarkusio/quarkus/pulls/0",
                "repos/quarkusio/quarkus/pulls", "repos/quarkusio/quarkus/issues/1", "orgs/other/members/a",
                "orgs/quarkusio/members/a/b", "orgs/quarkusio/members/a?x=1", "orgs/quarkusio/members/",
                "repos/quarkusio/quarkus/commits?author=a/b&sha=" + SHA + "&per_page=1",
                "repos/quarkusio/quarkus/commits?author=a?b&sha=" + SHA + "&per_page=1",
                "repos/quarkusio/quarkus/commits?author=a&sha=main&per_page=1",
                "repos/quarkusio/quarkus/commits?author=a&sha=" + SHA + "&per_page=100",
                "repos/quarkusio/quarkusXio/pulls/1", "/repos/quarkusio/quarkus/pulls/1")) {
            assertThrows(IllegalArgumentException.class, () -> client.command(List.of("api", path)), path);
        }
        assertThrows(IllegalArgumentException.class, () -> client.command(List.of("api")));
    }

    @Test
    void aBulkCallSearchesAllStatesForTheBatchAndAsksForTheNumber() {
        assertEquals(List.of("pr", "list", "--state", "all", "--search", "57715 56934", "--json",
                "number,additions,deletions,changedFiles", "--limit", "2"),
                GhClient.listArgs(List.of(57715, 56934), "additions,deletions,changedFiles"));
        assertThrows(IllegalArgumentException.class, () -> GhClient.listArgs(List.of(), "comments"));
    }

    @Test
    void batchesKeepTheSearchWithin256Characters() {
        List<Integer> prs = IntStream.range(0, 100).map(i -> 50000 + i).boxed().toList();

        List<List<Integer>> batches = GhClient.batches(prs);

        // "50000 50001 ..." is 6 characters per PR but the first: 43 PRs make 257, 42 make 251.
        assertEquals(List.of(42, 42, 16), batches.stream().map(List::size).toList());
        assertEquals(prs, batches.stream().flatMap(List::stream).toList());
        batches.forEach(b -> assertTrue(String.join(" ", b.stream().map(String::valueOf).toList()).length() <= 256));
        assertEquals(List.of(List.of(1)), GhClient.batches(List.of(1)));
        assertEquals(List.of(), GhClient.batches(List.of()));
    }

    @Test
    void bulkResultsAreKeyedByNumberWithoutUnrequestedIncompleteOrUnnumberedPrs(@TempDir Path dir) throws IOException {
        Path gh = dir.resolve("gh");
        Files.writeString(gh, """
                #!/bin/sh
                cat <<'JSON'
                [{"number":1,"additions":5,"deletions":2,"changedFiles":1},
                 {"number":7,"additions":1,"deletions":1,"changedFiles":1},
                 {"additions":9,"deletions":9,"changedFiles":9,"headRefOid":"def","statusCheckRollup":[],"comments":[]},
                 {"number":2,"additions":null,"deletions":2,"changedFiles":1},
                 {"number":3,"headRefOid":"abc","statusCheckRollup":[],"comments":[{"author":{"login":"x"},\
                "body":"hi","createdAt":"2026-01-01T00:00:00Z"}]}]
                JSON
                """);
        gh.toFile().setExecutable(true);
        GhClient bulk = new GhClient(config(gh.toString(), Duration.ofSeconds(10)), JsonMapper.builder().build());

        assertEquals(Map.of(1, new GhSize(5, 2, 1)), bulk.sizes(List.of(1, 2, 3)));
        assertEquals(Map.of(3, new GhChecks("abc", List.of())), bulk.checks(List.of(1, 2, 3)));
        assertEquals(Map.of(3, List.of(new GhComment("x", "hi", "2026-01-01T00:00:00Z"))),
                bulk.comments(List.of(1, 2, 3)));
    }

    static ReviewConfig config(String binary, Duration timeout) {
        return new ReviewConfig() {
            @Override
            public Gh gh() {
                return new Gh() {
                    @Override
                    public String repo() {
                        return "quarkusio/quarkus";
                    }

                    @Override
                    public String binary() {
                        return binary;
                    }

                    @Override
                    public Duration timeout() {
                        return timeout;
                    }

                    @Override
                    public Duration cacheTtl() {
                        return Duration.ofHours(1);
                    }
                };
            }

            @Override
            public Provenance provenance() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Jev jev() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Path outputDir() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
