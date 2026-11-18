package io.quarkus.prreview.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.prreview.it.support.ClaudeWireMock;
import io.quarkus.prreview.it.support.FakeGh;
import io.quarkus.prreview.it.support.JevWireMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

/** {@code review <pr>...} end to end: per-PR errors are noted and the run goes on; the exit-code rule. */
@QuarkusMainTest
@WithTestResource(FakeGh.class)
class ReviewListTest {

    static final Path CALLS_LOG = FakeGh.CALLS_LOG;

    static JevWireMock jev;
    static ClaudeWireMock claude;

    @TempDir
    Path out;

    @BeforeAll
    static void startStubs() {
        jev = JevWireMock.start();
        claude = ClaudeWireMock.start();
    }

    @AfterAll
    static void stopStubs() {
        jev.close();
        claude.close();
    }

    @BeforeEach
    void resetStubs() {
        jev.reset();
        claude.reset();
    }

    @Test
    void aFailureInTheMiddleIsNotedAndTheRunGoesOn(QuarkusMainLauncher launcher) throws IOException {
        // Only PR 102's diff has this line.
        jev.failWhenBodyContains("assertEquals(2, new Foo().x)", 400,
                "{\"error\": {\"type\": \"max_tokens_exceeded\", \"message\": \"input too long\"}}");

        LaunchResult result = launcher.launch("review", "101", "102", "104", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getOutput());
        List<String> stdout = result.getOutputStream();
        assertEquals(List.of("PR #101 - test adequacy", "PR #102 - test adequacy", "PR #104 - test adequacy"),
                stdout.stream().filter(l -> l.startsWith("PR #")).toList(), result.getOutput());
        String middle = block(stdout, 102);
        assertTrue(middle.contains("  not classified: jev: "), middle);
        assertTrue(middle.contains("max_tokens_exceeded"), middle);
        assertTrue(block(stdout, 101).contains("Summary (Claude): "), result.getOutput());
        assertTrue(block(stdout, 104).contains("Summary (Claude): "), result.getOutput());
        // Size comes from its own gh call: 104's fails, and only its size is unavailable.
        assertTrue(block(stdout, 101).contains("Size: 2 files changed, +12 -3\n"), result.getOutput());
        assertTrue(block(stdout, 102).contains("Size: unavailable\n"), result.getOutput());
        assertTrue(block(stdout, 104).contains("Size: unavailable\n"), result.getOutput());
        assertTrue(Files.readString(out.resolve("review-104.md")).contains("## Size (GitHub)\n\nunavailable\n"));
        // Checks come from their own gh call too. 101's failed checks don't change its classification or the
        // exit code; 102 has no answer for its checks.
        assertTrue(block(stdout, 101).contains("CI: 5 of 9 not passed\n"), result.getOutput());
        assertTrue(block(stdout, 101).contains("  CI Merge Gate (status)  "), result.getOutput());
        assertTrue(block(stdout, 102).contains("CI: unavailable\n"), result.getOutput());
        assertTrue(block(stdout, 104).contains("CI: all 3 passed, skipped or neutral\n"), result.getOutput());
        assertTrue(Files.readString(out.resolve("review-101.md"))
                .contains("## CI checks (GitHub)\n\n5 of 9 not passed.\n"));
        // A blank line between blocks.
        int second = stdout.indexOf("PR #102 - test adequacy");
        assertEquals("", stdout.get(second - 1), result.getOutput());

        String report102 = Files.readString(out.resolve("review-102.md"));
        assertTrue(report102.contains("## Not classified"), report102);
        assertTrue(report102.contains("max_tokens_exceeded"), report102);
        assertFalse(report102.contains("## Classification"), report102);
        assertTrue(Files.readString(out.resolve("review-101.md")).contains("## Classification"));
        assertTrue(Files.readString(out.resolve("review-104.md")).contains("## Classification"));
        assertEquals(3, jev.posts().size());
        assertEquals(2, claude.posts().size());
    }

    @Test
    void whenEveryPrFailsTheExitCodeIsOne(QuarkusMainLauncher launcher) throws IOException {
        LaunchResult result = launcher.launch("review", "404", "406", "--output-dir", out.toString());

        assertEquals(1, result.exitCode(), result.getOutput());
        assertTrue(block(result.getOutputStream(), 404).contains("  not classified: gh: no pull requests found"),
                result.getOutput());
        assertTrue(block(result.getOutputStream(), 406).contains("  not classified: gh: "), result.getOutput());
        assertTrue(Files.readString(out.resolve("review-404.md")).startsWith("# PR #404\n"));
        String report406 = Files.readString(out.resolve("review-406.md"));
        assertTrue(report406.contains("too_large"), report406);
        // gh pr view worked and only the diff failed, so the report keeps the title.
        assertTrue(report406.startsWith("# PR #406: Reformat everything\n"), report406);
        // GitHub refused the diff, but its size counts are still known.
        assertTrue(block(result.getOutputStream(), 406).contains("Size: 1244 files changed, +17 -19751\n"),
                result.getOutput());
        assertTrue(report406.contains("- Changed files: 1244\n- Lines added: 17\n- Lines deleted: 19751\n"), report406);
        assertTrue(block(result.getOutputStream(), 404).contains("Size: unavailable\n"), result.getOutput());
        assertTrue(block(result.getOutputStream(), 406).contains("CI: unavailable\n"), result.getOutput());
        assertTrue(report406.contains("## CI checks (GitHub)\n\nunavailable\n"), report406);
        assertEquals(0, jev.posts().size());
        assertEquals(0, claude.posts().size());
    }

    @Test
    void aSinglePrOverTheConfiguredLimitIsAnError(QuarkusMainLauncher launcher) throws IOException {
        System.setProperty("pr-review.jev.max-submission-chars", "1000");
        try {
            LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

            assertEquals(1, result.exitCode(), result.getOutput());
            String block = block(result.getOutputStream(), 101);
            assertTrue(block.matches("(?s).*  not classified: submission \\d+ chars exceeds limit 1000\n.*"), block);
            String report = Files.readString(out.resolve("review-101.md"));
            assertTrue(report.contains("## Not classified"), report);
            assertTrue(report.contains("(limit 1000)"), report);
            // Too large for jev, but its size is reported.
            assertTrue(block.contains("Size: 2 files changed, +12 -3\n"), block);
            assertTrue(report.contains("## Size (GitHub)\n\n- Changed files: 2\n"), report);
            assertEquals(0, jev.posts().size());
        } finally {
            System.clearProperty("pr-review.jev.max-submission-chars");
        }
    }

    @Test
    void anUnwritableOutputDirIsAnError(QuarkusMainLauncher launcher) throws IOException {
        Path file = Files.writeString(out.resolve("file"), "x");

        LaunchResult result = launcher.launch("review", "101", "--output-dir", file.resolve("reports").toString());

        assertEquals(1, result.exitCode(), result.getOutput());
        String block = block(result.getOutputStream(), 101);
        assertTrue(block.contains("Summary (Claude): "), block);
        assertTrue(block.contains("Report: not written: "), block);
        try (var files = Files.list(out)) {
            assertEquals(List.of(file), files.toList(), "no partial file");
        }
    }

    @Test
    void aMissingJevKeyStopsTheRunBeforeAnyPr(QuarkusMainLauncher launcher) throws IOException {
        // "not-set" is what application.yml resolves to when TYPESAFE_API_KEY is not set; profile-qualified, so it
        // outranks the test profile's key.
        System.setProperty("%test.quarkus.langchain4j.typesafe.api-key", "not-set");
        long ghCalls = ghCalls();
        try {
            LaunchResult result = launcher.launch("review", "101", "102", "--output-dir", out.toString());

            assertEquals(1, result.exitCode(), result.getOutput());
            assertTrue(result.getErrorOutput().contains("error: ") && result.getErrorOutput().contains(
                    "TYPESAFE_API_KEY"), result.getErrorOutput());
            assertEquals("", String.join("", result.getOutputStream().stream()
                    .filter(l -> l.startsWith("PR #")).toList()), result.getOutput());
            assertEquals(ghCalls, ghCalls(), "no gh invocation");
            try (var files = Files.list(out)) {
                assertEquals(List.of(), files.toList(), "nothing written");
            }
            assertEquals(0, jev.posts().size());
        } finally {
            System.clearProperty("%test.quarkus.langchain4j.typesafe.api-key");
        }
    }

    @Test
    void anEmptyJevKeyIsAMissingKey(QuarkusMainLauncher launcher) throws IOException {
        // An exported but empty TYPESAFE_API_KEY, read through application.yml's expression; the system property
        // stands in for the environment variable, and the profile-qualified one outranks the test profile's key.
        System.setProperty("%test.quarkus.langchain4j.typesafe.api-key", "${TYPESAFE_API_KEY:not-set}");
        System.setProperty("TYPESAFE_API_KEY", "");
        try {
            LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

            assertEquals(1, result.exitCode(), result.getOutput());
            assertEquals(List.of("error: no jev API key; set TYPESAFE_API_KEY"), result.getErrorStream(),
                    result.getErrorOutput());
            assertEquals(0, jev.posts().size());
        } finally {
            System.clearProperty("%test.quarkus.langchain4j.typesafe.api-key");
            System.clearProperty("TYPESAFE_API_KEY");
        }
    }

    @Test
    void helpWorksWithoutAJevKey(QuarkusMainLauncher launcher) {
        System.setProperty("%test.quarkus.langchain4j.typesafe.api-key", "not-set");
        try {
            LaunchResult result = launcher.launch("review", "--help");

            assertEquals(0, result.exitCode(), result.getOutput());
            assertTrue(result.getOutput().contains("--output-dir"), result.getOutput());
        } finally {
            System.clearProperty("%test.quarkus.langchain4j.typesafe.api-key");
        }
    }

    @Test
    void duplicatePrsAreProcessedOnceAtTheirFirstPosition(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("review", "101", "102", "101", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getOutput());
        assertEquals(List.of("PR #101 - test adequacy", "PR #102 - test adequacy"),
                result.getOutputStream().stream().filter(l -> l.startsWith("PR #")).toList(), result.getOutput());
        assertEquals(2, jev.posts().size());
    }

    @Test
    void noChecksIsNoneAndUnreadableChecksAreUnavailable(QuarkusMainLauncher launcher) throws IOException {
        LaunchResult result = launcher.launch("review", "103", "406", "--output-dir", out.toString());

        assertTrue(block(result.getOutputStream(), 103).contains("CI: none\n"), result.getOutput());
        assertTrue(block(result.getOutputStream(), 406).contains("CI: unavailable\n"), result.getOutput());
        assertTrue(Files.readString(out.resolve("review-103.md")).contains("## CI checks (GitHub)\n\nnone\n"));
    }

    @Test
    void failureDetailsComeOnlyFromTheHeadCommitsBotComment(QuarkusMainLauncher launcher) throws IOException {
        LaunchResult result = launcher.launch("review", "101", "106", "107", "108", "--output-dir", out.toString());
        List<String> stdout = result.getOutputStream();

        // 101: quarkus-bot's comment for the head commit names the failing tests, one flaky.
        String pr101 = block(stdout, 101);
        assertTrue(pr101.matches("(?s).*JVM Tests - JDK 21 \\(Quarkus CI\\) +FAILURE +io\\.quarkus\\.vertx\\.http"
                + "\\.filters\\.GracefulShutdownFilterTest\\.test +yes +Timeout waiting for response - "
                + "java\\.lang\\.RuntimeException\n.*"), pr101);
        assertTrue(pr101.matches("(?s).*JVM Integration Tests - JDK 17 \\(Quarkus CI\\) +FAILURE +Failed to execute "
                + "goal .*"), pr101);
        assertFalse(pr101.contains("CompressionEnabledTestCase"), "flaky in a job that passed: " + pr101);
        String report101 = Files.readString(out.resolve("review-101.md"));
        assertTrue(report101.contains("[`io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test`]("
                + "https://develocity.quarkus.io/scans/tests?"), report101);
        assertTrue(report101.contains(" · [scan](https://develocity.quarkus.io/s/hzvs2pygcxpvk) |"), report101);

        // 106: only a comment for an earlier commit; 107: quarkus-bot never commented; 108: comments unreadable.
        assertTrue(block(stdout, 106).contains("details unavailable"), result.getOutput());
        assertFalse(block(stdout, 106).contains("MaxFrameSizeTest"), result.getOutput());
        assertTrue(block(stdout, 107).contains("no source for failure details"), result.getOutput());
        assertTrue(block(stdout, 108).contains("CI: 1 of 2 not passed\n"), result.getOutput());
        assertTrue(block(stdout, 108).contains("details unavailable"), result.getOutput());
    }

    @Test
    void factsAreReadInBulkWithOneListCallPerFact(QuarkusMainLauncher launcher) throws IOException {
        long before = ghCalls();
        LaunchResult result = launcher.launch("review", "101", "103", "106", "107", "--output-dir", out.toString());

        List<String> calls = callsSince(before);
        assertEquals(4, calls.stream().filter(c -> c.startsWith("pr list ")).count(), calls.toString());
        assertTrue(calls.contains("pr list --state all --search 101 103 106 107 --json number,author,body,baseRefOid,"
                + "commits --limit 4 -R quarkusio/quarkus"), calls.toString());
        assertTrue(calls.contains("pr list --state all --search 101 103 106 107 --json number,additions,deletions,"
                + "changedFiles --limit 4 -R quarkusio/quarkus"), calls.toString());
        // Comments only for the PRs with a check that did not pass.
        assertTrue(calls.contains("pr list --state all --search 101 106 107 --json number,comments --limit 3 "
                + "-R quarkusio/quarkus"), calls.toString());
        assertEquals(List.of(), factViews(calls), "no per-PR fact call");
        List<String> stdout = result.getOutputStream();
        assertTrue(block(stdout, 101).contains("Size: 2 files changed, +12 -3\n"), result.getOutput());
        assertTrue(block(stdout, 101).contains("CI: 5 of 9 not passed\n"), result.getOutput());
        assertTrue(block(stdout, 101).contains("GracefulShutdownFilterTest"), result.getOutput());
        assertTrue(block(stdout, 103).contains("CI: none\n"), result.getOutput());
        assertTrue(block(stdout, 107).contains("no source for failure details"), result.getOutput());
    }

    @Test
    void aPrMissingFromTheBulkResultsOrInAFailedBulkCallIsReadOnItsOwn(QuarkusMainLauncher launcher)
            throws IOException {
        long before = ghCalls();
        LaunchResult result = launcher.launch("review", "107", "109", "--output-dir", out.toString());

        assertEquals(List.of("pr view 109 --json additions,deletions,changedFiles -R quarkusio/quarkus",
                "pr view 109 --json headRefOid,statusCheckRollup -R quarkusio/quarkus",
                "pr view 109 --json comments -R quarkusio/quarkus"), factViews(callsSince(before)));
        // The same output as for 107, whose facts came in bulk: only the number and title differ.
        assertEquals(throughReport(block(result.getOutputStream(), 107)).replace("107", "109"),
                throughReport(block(result.getOutputStream(), 109)));

        before = ghCalls();
        result = launcher.launch("review", "101", "111", "--output-dir", out.toString());

        assertEquals(6, factViews(callsSince(before)).size(), callsSince(before).toString());
        assertTrue(block(result.getOutputStream(), 101).contains("CI: 5 of 9 not passed\n"), result.getOutput());
        assertTrue(block(result.getOutputStream(), 111).contains("no source for failure details"),
                result.getOutput());
    }

    @Test
    void oneHundredCommentsAreReadAgainAndUnrequestedPrsAreDropped(QuarkusMainLauncher launcher)
            throws IOException {
        long before = ghCalls();
        LaunchResult result = launcher.launch("review", "110", "--output-dir", out.toString());

        assertEquals(List.of("pr view 110 --json comments -R quarkusio/quarkus"), factViews(callsSince(before)));
        // The full list has quarkus-bot's welcome comment; the cut-off one would have said "no source".
        String block = block(result.getOutputStream(), 110);
        assertTrue(block.contains("details unavailable"), block);
        assertTrue(block.contains("Size: 1 files changed, +1 -1\n"), block);
        // The search also returned PR 101, which nobody asked for.
        assertFalse(result.getOutput().contains("#101"), result.getOutput());
        assertFalse(Files.exists(out.resolve("review-101.md")));
    }

    @Test
    void everyPrShowsWhoWroteItWithOneLookupPerPr(QuarkusMainLauncher launcher) throws IOException {
        long before = ghCalls();
        LaunchResult result = launcher.launch("review", "101", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        List<String> calls = callsSince(before);
        assertEquals(List.of("api repos/quarkusio/quarkus/pulls/101 --jq .author_association"), apiCalls(calls));
        // Who wrote it came from the bulk read: no per-PR call for it.
        assertEquals(List.of(), authorshipViews(calls));
        assertTrue(calls.contains("pr list --state all --search 101 --json number,author,body,baseRefOid,commits "
                + "--limit 1 -R quarkusio/quarkus"), calls.toString());
        assertTrue(block(result.getOutputStream(), 101).contains("Author: alice (team) · AI: none declared\n"),
                result.getOutput());
        String report = Files.readString(out.resolve("review-101.md"));
        assertTrue(report.contains("## Provenance (GitHub)\n\nAuthor: alice (team) · AI: none declared\n\nRead at "),
                report);
    }

    @Test
    void aFailedLookupOrAnUnreadableAuthorshipNeverStopsAPrBeingClassified(QuarkusMainLauncher launcher)
            throws IOException {
        // 104's association lookup fails; 112 has no authorship at all.
        LaunchResult result = launcher.launch("review", "104", "112", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        String block104 = block(result.getOutputStream(), 104);
        assertTrue(block104.contains("  behaviour "), block104);
        assertTrue(block104.contains("Author: carol (other: association lookup failed: gh: Server Error (HTTP 502))"
                + " · AI: none declared\n"), block104);
        String block112 = block(result.getOutputStream(), 112);
        assertTrue(block112.contains("  behaviour "), block112);
        assertTrue(block112.contains("Author: unavailable\n"), block112);
        assertTrue(Files.readString(out.resolve("review-112.md")).contains("## Provenance (GitHub)\n\nunavailable\n"));
    }

    @Test
    void aPrThatCouldNotBeFetchedStillShowsWhoWroteIt(QuarkusMainLauncher launcher) throws IOException {
        LaunchResult result = launcher.launch("review", "114", "--output-dir", out.toString());

        String block = block(result.getOutputStream(), 114);
        assertTrue(block.contains("  not classified: gh: GraphQL"), block);
        assertTrue(block.contains("Author: gina (community) · AI: none declared\n"), block);
        assertTrue(Files.readString(out.resolve("review-114.md"))
                .contains("## Provenance (GitHub)\n\nAuthor: gina (community) · AI: none declared\n"));
    }

    @Test
    void aSecondCommitAuthorIsLookedUpOnceWithCachedCallsAndATrailerCoAuthorNever(QuarkusMainLauncher launcher)
            throws IOException {
        // 113: hank's PR with a commit by helper that names trailer-person as co-author; 115: ivy's PR with a commit
        // by helper too.
        long before = ghCalls();
        LaunchResult result = launcher.launch("review", "113", "115", "--output-dir", out.toString());

        assertEquals(0, result.exitCode(), result.getErrorOutput());
        List<String> api = apiCalls(callsSince(before));
        assertEquals(List.of("api repos/quarkusio/quarkus/pulls/113 --jq .author_association",
                "api orgs/quarkusio/members/helper -i --cache 3600s",
                "api repos/quarkusio/quarkus/commits?author=helper&sha=1405df66cbe219b0bf6355bc3d60361a8376b6b4"
                        + "&per_page=1 --cache 3600s",
                "api repos/quarkusio/quarkus/pulls/115 --jq .author_association"), api);
        assertFalse(callsSince(before).stream().anyMatch(c -> c.contains("trailer-person")), api.toString());
        assertTrue(block(result.getOutputStream(), 113).contains(
                "Author: hank (community) · AI: none declared · Commits: helper (community)\n"), result.getOutput());
        assertTrue(block(result.getOutputStream(), 115).contains(
                "Author: ivy (first-time) · AI: none declared · Commits: helper (community)\n"), result.getOutput());
    }

    @Test
    void whoWroteEachPrIsReadInBulkAndOnItsOwnOnlyWhenTheBulkReadFallsShort(QuarkusMainLauncher launcher)
            throws IOException {
        // 103's bulk answer has exactly 100 commits, where gh cuts the list off.
        long before = ghCalls();
        LaunchResult result = launcher.launch("review", "101", "103", "106", "--output-dir", out.toString());
        List<String> calls = callsSince(before);
        assertTrue(calls.contains("pr list --state all --search 101 103 106 --json number,author,body,baseRefOid,"
                + "commits --limit 3 -R quarkusio/quarkus"), calls.toString());
        assertEquals(List.of("pr view 103 --json author,body,baseRefOid,commits -R quarkusio/quarkus"),
                authorshipViews(calls));
        // The per-PR answer has one commit, by the PR author: no one else to list.
        assertTrue(block(result.getOutputStream(), 103).contains("Author: bob (community) · AI: none declared\n"),
                result.getOutput());

        // 109 is left out of every list.
        before = ghCalls();
        launcher.launch("review", "107", "109", "--output-dir", out.toString());
        assertEquals(List.of("pr view 109 --json author,body,baseRefOid,commits -R quarkusio/quarkus"),
                authorshipViews(callsSince(before)));

        // Listing 111 fails, so every PR of that batch is read on its own.
        before = ghCalls();
        result = launcher.launch("review", "101", "111", "--output-dir", out.toString());
        assertEquals(List.of("pr view 101 --json author,body,baseRefOid,commits -R quarkusio/quarkus",
                "pr view 111 --json author,body,baseRefOid,commits -R quarkusio/quarkus"),
                authorshipViews(callsSince(before)));
        assertTrue(block(result.getOutputStream(), 101).contains("Author: alice (team) · AI: none declared\n"),
                result.getOutput());
    }

    /** The per-PR {@code gh pr view} calls for who wrote a PR, in order. */
    private static List<String> authorshipViews(List<String> calls) {
        return calls.stream().filter(c -> c.startsWith("pr view ") && c.contains(" author,body,")).toList();
    }

    /** The {@code gh api} calls, in order. */
    private static List<String> apiCalls(List<String> calls) {
        return calls.stream().filter(c -> c.startsWith("api ")).toList();
    }

    /** A block up to and including its report line, without any log line printed after it. */
    private static String throughReport(String block) {
        return block.substring(0, block.indexOf('\n', block.indexOf("Report: ")) + 1);
    }

    /** The per-PR {@code gh pr view} calls for a GitHub fact, in order. */
    private static List<String> factViews(List<String> calls) {
        return calls.stream().filter(c -> c.startsWith("pr view ") && (c.contains(" additions,")
                || c.contains(" headRefOid,") || c.contains(" comments "))).toList();
    }

    private static List<String> callsSince(long before) throws IOException {
        try (var lines = Files.lines(CALLS_LOG)) {
            return lines.skip(before).filter(l -> !l.startsWith("sleep-pid ")).toList();
        }
    }

    /** The lines of one PR's block, from its header to the next blank line. */
    private static String block(List<String> stdout, int pr) {
        int start = stdout.indexOf("PR #" + pr + " - test adequacy");
        assertTrue(start >= 0, "no block for PR #" + pr + " in " + stdout);
        StringBuilder block = new StringBuilder();
        for (int i = start; i < stdout.size() && !stdout.get(i).isEmpty(); i++) {
            block.append(stdout.get(i)).append('\n');
        }
        return block.toString();
    }

    private static long ghCalls() throws IOException {
        if (!Files.exists(CALLS_LOG)) {
            return 0;
        }
        try (var lines = Files.lines(CALLS_LOG)) {
            return lines.count();
        }
    }
}
