package io.quarkus.prreview.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.output.TokenUsage;
import io.quarkus.prreview.classification.checks.TestAdequacyService;
import io.quarkus.prreview.classification.github.model.Approval;
import io.quarkus.prreview.classification.github.model.CiCheck;
import io.quarkus.prreview.classification.github.model.CiChecks;
import io.quarkus.prreview.classification.github.model.FailedCheck;
import io.quarkus.prreview.classification.github.model.FailureRow;
import io.quarkus.prreview.classification.github.model.FlakyTest;
import io.quarkus.prreview.classification.github.model.PrSize;
import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.ProjectContext;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.classification.model.SourceResult;
import io.quarkus.prreview.classification.provenance.model.AiLabel;
import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Provenance;
import io.quarkus.prreview.classification.provenance.model.Who;
import io.quarkus.prreview.report.summary.FallbackSummary;
import io.quarkus.prreview.report.summary.Summary;
import io.quarkus.prreview.triage.model.ChangedFile;
import io.quarkus.prreview.triage.model.PrContext;
import io.quarkus.prreview.triage.model.PrOutcome;
import io.quarkus.prreview.triage.model.PullRequest;

class RenderingTest {

    static final Summary CLAUDE = new Summary("Runtime behaviour changes; no tests were changed.", "claude-opus-5-5",
            1234, new TokenUsage(300, 25));
    static final Path REPORT = Path.of("reports/review-57720.md");
    static final ProjectContext CONTEXT = new ProjectContext("contexts/test.txt", "Project context: a test repository.");

    /**
     * The four test-adequacy answers: {@code behaviour} and {@code reach} vary; {@code tests-changed} (0.12) and
     * {@code assert} (nothing-executed 0.77) are fixed at values no varied number contains.
     */
    static Classification classification(String title, String path, double behaviour, String chosen,
            Map<String, Double> options) {
        PullRequest pr = new PullRequest(57720, title, "https://github.com/quarkusio/quarkus/pull/57720", "OPEN", false, "");
        List<ChangedFile> files = List.of(new ChangedFile(path, ChangedFile.Kind.MODIFIED, null, false));
        CheckResult check = new CheckResult("test-adequacy", List.of(
                new QuestionResult("behaviour", TestAdequacyService.BEHAVIOUR.text(),
                        new QuestionResult.YesNo(behaviour)),
                new QuestionResult("tests-changed",
                        TestAdequacyService.TESTS_CHANGED.text(), new QuestionResult.YesNo(0.12)),
                new QuestionResult("reach", TestAdequacyService.REACH.text(),
                        new QuestionResult.Choice(chosen, TestAdequacyService.REACH.options(), options)),
                new QuestionResult("assert", TestAdequacyService.ASSERTS.text(),
                        new QuestionResult.Choice("nothing-executed", TestAdequacyService.ASSERTS.options(),
                                probs("nothing-executed", 0.77, "none", 0.23)))));
        return new Classification(new PrContext(pr, files), List.of(check), "jev-1", new TokenUsage(1200, 40), 5000,
                1667, 60000, CONTEXT);
    }

    static Classification sample(double behaviour, String chosen, Map<String, Double> options) {
        return classification("Bump jackson-bom", "pom.xml", behaviour, chosen, options);
    }

    /** The terminal block for a classified PR whose report was written to {@link #REPORT}. */
    static String terminal(Classification classification, Summary summary) {
        return TerminalRenderer.render(PrOutcome.answered(classification, summary),
                new ReportWriter.Written(REPORT));
    }

    static PrOutcome notAnswered(PullRequest pr, String reason, String message) {
        return PrOutcome.notAnswered(57715, List.of("test-adequacy"), pr, reason, message, "jev-latest", 215269,
                71757, 60000);
    }

    static Map<String, Double> probs(Object... pairs) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Double) pairs[i + 1]);
        }
        return map;
    }

    @Test
    void terminalBlockMatchesTheSpecLayout() {
        String block = terminal(sample(0.88, "none",
                probs("none", 0.83, "some", 0.17)), CLAUDE);

        assertEquals("""
                PR #57720 - test adequacy
                  behaviour 0.88  tests-changed 0.12  reach: none 0.83  assert: nothing-executed 0.77
                Summary (Claude): Runtime behaviour changes; no tests were changed.
                Size: unavailable
                CI: unavailable
                Author: unavailable
                Report: reports/review-57720.md
                """, block);
    }

    @Test
    void probabilitiesAreOnlyShownNeverInterpreted() {
        String reference = null;
        for (double p : new double[] { 0.0, 0.4999, 0.5, 1.0, 0.88 }) {
            Classification c = sample(p, "none", probs("none", p));
            String terminal = terminal(c, CLAUDE);
            String report = MarkdownReport.render(c, CLAUDE);

            assertTrue(terminal.contains("behaviour " + AnswerFormat.rounded(p) + "  "), terminal);
            assertTrue(report.contains("| " + AnswerFormat.exact(p) + " |"), report);
            // Apart from the numbers, the output is the same for every value.
            String normalized = (terminal + report).replace(AnswerFormat.rounded(p), "P").replace(AnswerFormat.exact(p), "P");
            if (reference == null) {
                reference = normalized;
            }
            assertEquals(reference, normalized);
        }
        assertEquals("0.50", AnswerFormat.rounded(0.4999));
        assertEquals("0.4999", AnswerFormat.exact(0.4999));
        assertEquals("0.0001", AnswerFormat.exact(0.0001));
    }

    @Test
    void shownOptionIsJevsChoiceEvenWhenAnotherIsMoreLikely() {
        Classification c = sample(0.5, "undetermined", probs("all", 0.6, "undetermined", 0.3));

        assertTrue(terminal(c, CLAUDE).contains("reach: undetermined 0.30"));
        String report = MarkdownReport.render(c, CLAUDE);
        assertTrue(report.contains("jev's choice: `undetermined`"), report);
        assertTrue(report.contains("| `all` | " + TestAdequacyService.REACH.options().get("all") + " | 0.6 |"), report);
        assertTrue(report.contains("| `none` | " + TestAdequacyService.REACH.options().get("none")
                + " | not reported |"), report);
    }

    @Test
    void choiceWithoutProbabilitiesShowsTheOptionOnly() {
        Classification c = sample(0.5, "some", Map.of());

        assertTrue(terminal(c, CLAUDE).contains("  reach: some  assert:"));
        assertEquals("jev classification: behaviour 0.50; tests-changed 0.12; reach some; assert nothing-executed 0.77.",
                FallbackSummary.of(c).text());
    }

    @Test
    void reportSectionsAppearInOrderWithEveryOption() {
        String report = MarkdownReport.render(sample(0.88, "none", probs("none", 0.83)), CLAUDE);

        assertTrue(report.startsWith("# PR #57720: Bump jackson-bom\n\nLink: https://github.com/quarkusio/quarkus/pull/57720"
                + " · State: OPEN\n"), report);
        int classification = report.indexOf("\n## Classification\n");
        int summary = report.indexOf("\n## Summary\n");
        int files = report.indexOf("\n## Changed files\n");
        int run = report.indexOf("\n## Run\n");
        assertTrue(0 < classification && classification < summary && summary < files && files < run, report);
        // Each choice question has its own table listing every option.
        String assertTable = report.substring(report.indexOf("\n### assert\n"));
        String reachTable = report.substring(report.indexOf("\n### reach\n"), report.indexOf("\n### assert\n"));
        TestAdequacyService.REACH.options().keySet().forEach(
                option -> assertTrue(reachTable.contains("| `" + option + "` |"), "reach " + option));
        TestAdequacyService.ASSERTS.options().keySet().forEach(
                option -> assertTrue(assertTable.contains("| `" + option + "` |"), "assert " + option));
        assertTrue(report.contains("**Claude (claude-opus-5-5):** Runtime behaviour changes; no tests were changed.\n"),
                report);
        assertTrue(report.contains("- `pom.xml` (modified)"), report);
        assertTrue(report.endsWith("""
                ## Run

                - jev model: jev-1
                - Submission size: estimated 1667 tokens + 1000 for the answer (limit 60000 tokens)
                - Submission characters: 5000
                - Project context: contexts/test.txt (35 characters)
                - jev tokens: 1200 input, 40 output
                - Summary: Claude (claude-opus-5-5)
                - Claude submission: 1234 characters (system and user messages)
                - Claude tokens: 300 input, 25 output
                """), report);
    }

    @Test
    void reportFooterSaysWhenClaudeWasNotCalledOrReportedNoTokens() {
        Classification c = sample(0.88, "none", probs("none", 0.83));
        String notSent = MarkdownReport.render(c, FallbackSummary.of(c));
        assertTrue(notSent.endsWith("- Summary: fallback template\n- Claude submission: not sent\n"
                + "- Claude tokens: not reported\n"), notSent);

        String failed = MarkdownReport.render(c, new Summary(FallbackSummary.of(c).text(), null, 987, null));
        assertTrue(failed.endsWith("- Summary: fallback template\n"
                + "- Claude submission: 987 characters (system and user messages)\n"
                + "- Claude tokens: not reported\n"), failed);
    }

    @Test
    void untrustedTextCannotChangeTheStructure() {
        Classification c = classification("Fix | pipes\n## Injected\n| a | b |", "docs/a|b `x`.adoc", 0.5,
                "none", probs("none", 0.5));
        Summary summary = new Summary("Line one.\n# Heading\n| x |", "model|x");

        String report = MarkdownReport.render(c, summary);

        assertTrue(report.startsWith("# PR #57720: Fix | pipes ## Injected | a | b |\n"), report);
        assertTrue(report.contains("- `` docs/a|b `x`.adoc `` (modified)"), report);
        assertTrue(report.contains("**Claude (model|x):** Line one. # Heading | x |\n"), report);
        assertFalse(report.lines().anyMatch(l -> l.startsWith("## Injected") || l.startsWith("# Heading")), report);
        // Only the renderer's own sections are headings.
        assertEquals(List.of("# PR #57720: Fix | pipes ## Injected | a | b |", "## Size (GitHub)", "## CI checks (GitHub)",
                "## Provenance (GitHub)",
                "## Classification", "### reach", "### assert",
                "## Summary", "## Changed files", "## Run"),
                report.lines().filter(l -> l.startsWith("#")).toList());
        // Every table row has exactly its columns: a '|' from text is escaped.
        report.lines().filter(l -> l.startsWith("| ")).forEach(
                l -> assertEquals(4, l.replace("\\|", "").chars().filter(ch -> ch == '|').count(), l));
    }

    @Test
    void untrustedTextNeverStartsALine() {
        PullRequest pr = new PullRequest(1, "t", "> quote", "- list", false, "");
        Classification c = new Classification(new PrContext(pr, List.of()), sample(0.5, "undetermined",
                probs("undetermined", 0.5)).checks(), "jev", null, 1, 1, 2, CONTEXT);
        for (String text : List.of("# Heading", "```", "> quote", "- item", "1. item", "| a | b |", "~~~")) {
            String report = MarkdownReport.render(c, new Summary(text + "\n## Run\nmore", "m"));

            // Every line of the report starts with the renderer's own text.
            report.lines().filter(l -> !l.isEmpty()).forEach(l -> assertTrue(
                    l.startsWith("#") || l.startsWith("Link: ") || l.startsWith("|") || l.startsWith("Question: ")
                            || l.startsWith("jev's choice: ") || l.startsWith("**") || l.startsWith("- ")
                            || l.equals("unavailable"),
                    l));
            assertEquals(1, report.lines().filter(l -> l.equals("## Run")).count(), report);
            assertTrue(report.contains("\n**Claude (m):** " + text + " ## Run more\n"), report);
        }
        // GitHub text on the link line follows the fixed label too.
        String report = MarkdownReport.render(c, new Summary("x", "m"));
        assertTrue(report.contains("Link: > quote · State: - list\n"), report);
    }

    @Test
    void fallbackTemplateListsEveryAnswerInOrder() {
        Summary fallback = FallbackSummary.of(sample(0.88, "none", probs("none", 0.83)));

        assertEquals("jev classification: behaviour 0.88; tests-changed 0.12; reach none 0.83;"
                + " assert nothing-executed 0.77.", fallback.text());
        assertEquals("fallback template", fallback.source());
        assertTrue(terminal(sample(0.88, "none", probs("none", 0.83)), fallback).contains("Summary (fallback template): jev classification: behaviour 0.88;"));
    }

    @Test
    void notClassifiedTerminalBlockMatchesTheSpecLayout() {
        PrOutcome outcome = notAnswered(null, "jev: {\"error\":\n \"max_tokens_exceeded\"}", "body");

        assertEquals("""
                PR #57715 - test adequacy
                  not classified: jev: {"error": "max_tokens_exceeded"}
                Size: unavailable
                CI: unavailable
                Author: unavailable
                Report: reports/review-57720.md
                """, TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT)));
        assertEquals("""
                PR #57715 - test adequacy
                  not classified: jev: {"error": "max_tokens_exceeded"}
                Size: unavailable
                CI: unavailable
                Author: unavailable
                Report: not written: FileSystemException: /x: Not a directory
                """, TerminalRenderer.render(outcome, new ReportWriter.Failed(
                "FileSystemException: /x: Not a directory")));
    }

    @Test
    void reportNotWrittenAlsoEndsAClassifiedBlock() {
        String block = TerminalRenderer.render(PrOutcome.answered(sample(0.88, "none",
                probs("none", 0.83)), CLAUDE), new ReportWriter.Failed("AccessDeniedException: x"));

        assertTrue(block.endsWith("Summary (Claude): Runtime behaviour changes; no tests were changed.\n"
                + "Size: unavailable\n" + "CI: unavailable\n" + "Author: unavailable\n" + "Report: not written: AccessDeniedException: x\n"), block);
    }

    @Test
    void errorReportWithATitle() {
        PullRequest pr = new PullRequest(57715, "Big | change", "https://github.com/quarkusio/quarkus/pull/57715",
                "OPEN", false, "");
        String report = MarkdownReport.render(notAnswered(pr,
                "submission estimated at 71757 tokens + 1000 for the answer exceeds limit 60000",
                "submission is 215269 characters, estimated at 71757 tokens (3.0 characters per token);\nwith 1000"
                        + " tokens kept for the answer it is over the limit of 60000 tokens"));

        assertEquals("""
                # PR #57715: Big | change

                Link: https://github.com/quarkusio/quarkus/pull/57715 · State: OPEN

                ## Size (GitHub)

                unavailable

                ## CI checks (GitHub)

                unavailable

                ## Provenance (GitHub)

                unavailable

                ## Not classified

                Reason: submission estimated at 71757 tokens + 1000 for the answer exceeds limit 60000

                Message: submission is 215269 characters, estimated at 71757 tokens (3.0 characters per token); with 1000 tokens kept for the answer it is over the limit of 60000 tokens

                ## Run

                - jev model: jev-latest
                - Submission size: estimated 71757 tokens + 1000 for the answer (limit 60000 tokens)
                - Submission characters: 215269
                """, report);
        assertFalse(report.contains("## Classification"));
        assertFalse(report.contains("## Summary"));
    }

    @Test
    void aDraftShowsItsStateInTheReportAndOnThePrLine() {
        PullRequest pr = new PullRequest(57715, "Big | change", "https://github.com/quarkusio/quarkus/pull/57715",
                "OPEN", true, "");
        PrOutcome outcome = notAnswered(pr, "gh: x", "x");

        assertTrue(MarkdownReport.render(outcome).contains(
                "\nLink: https://github.com/quarkusio/quarkus/pull/57715 · State: OPEN (draft)\n"),
                MarkdownReport.render(outcome));
        String block = TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT));
        assertTrue(block.startsWith("PR #57715 - test adequacy · State: OPEN (draft)\n"), block);
    }

    @Test
    void aPrThatIsNotADraftKeepsItsPrLine() {
        PullRequest pr = new PullRequest(57715, "Big | change", "https://github.com/quarkusio/quarkus/pull/57715",
                "OPEN", false, "");

        String block = TerminalRenderer.render(notAnswered(pr, "gh: x", "x"), new ReportWriter.Written(REPORT));
        assertTrue(block.startsWith("PR #57715 - test adequacy\n"), block);
        String unread = TerminalRenderer.render(notAnswered(null, "gh: x", "x"), new ReportWriter.Written(REPORT));
        assertTrue(unread.startsWith("PR #57715 - test adequacy\n"), unread);
    }

    @Test
    void errorReportWithoutATitleShowsOnlyWhatIsKnown() {
        PrOutcome outcome = PrOutcome.notAnswered(404, List.of("test-adequacy"), null,
                "gh: no pull requests found", "no pull requests found\n## Run\n<!-- hidden", null, null, null, null);

        assertEquals("""
                # PR #404

                ## Size (GitHub)

                unavailable

                ## CI checks (GitHub)

                unavailable

                ## Provenance (GitHub)

                unavailable

                ## Not classified

                Reason: gh: no pull requests found

                Message: no pull requests found ## Run &lt;!-- hidden

                ## Run

                - jev model: not reached
                """, MarkdownReport.render(outcome));
    }

    @Test
    void sizeIsShownForClassifiedAndNotClassifiedPrs() {
        PrSize size = new PrSize(61, 933, 197);
        PrOutcome classified = PrOutcome.answered(sample(0.88, "none", probs("none", 0.83)),
                CLAUDE);
        classified = new PrOutcome(classified.number(), classified.checks(), classified.pullRequest(), classified.jev(),
                new SourceResult.Available<>(size), classified.ci(), classified.provenance());

        assertTrue(TerminalRenderer.render(classified, new ReportWriter.Written(REPORT)).endsWith("""
                Summary (Claude): Runtime behaviour changes; no tests were changed.
                Size: 61 files changed, +933 -197
                CI: unavailable
                Author: unavailable
                Report: reports/review-57720.md
                """));
        assertTrue(MarkdownReport.render(classified).contains("""
                State: OPEN

                ## Size (GitHub)

                - Changed files: 61
                - Lines added: 933
                - Lines deleted: 197

                ## CI checks (GitHub)

                unavailable

                ## Provenance (GitHub)

                unavailable

                ## Classification
                """), MarkdownReport.render(classified));

        PrOutcome titleLess = PrOutcome.notAnswered(404, List.of("test-adequacy"), null, "gh: no pull requests found",
                "no pull requests found", null, null, null, null);
        titleLess = new PrOutcome(404, titleLess.checks(), null, titleLess.jev(), new SourceResult.Available<>(size),
                titleLess.ci(), titleLess.provenance());
        assertEquals("""
                PR #404 - test adequacy
                  not classified: gh: no pull requests found
                Size: 61 files changed, +933 -197
                CI: unavailable
                Author: unavailable
                Report: reports/review-57720.md
                """, TerminalRenderer.render(titleLess, new ReportWriter.Written(REPORT)));
        assertTrue(MarkdownReport.render(titleLess).startsWith("""
                # PR #404

                ## Size (GitHub)

                - Changed files: 61
                """), MarkdownReport.render(titleLess));
    }

    static final Instant READ_AT = Instant.parse("2026-11-18T10:15:00.123456Z");

    static PrOutcome withProvenance(PrOutcome outcome, Provenance provenance) {
        return new PrOutcome(outcome.number(), outcome.checks(), outcome.pullRequest(), outcome.jev(), outcome.size(),
                outcome.ci(), new SourceResult.Available<>(provenance));
    }

    @Test
    void provenanceIsOneAuthorLineWithTheReadTime() {
        Provenance provenance = new Provenance(Person.of("app/copilot-swe-agent", Who.BOT),
                List.of(Person.of("alice", Who.COMMUNITY), Person.other("bob", "lookup limit")),
                new AiLabel(AiLabel.Kind.AGENT, "app/copilot-swe-agent"), READ_AT);
        PrOutcome outcome = withProvenance(PrOutcome.answered(sample(0.88, "none",
                probs("none", 0.83)), CLAUDE), provenance);

        assertTrue(TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT)).endsWith("""
                CI: unavailable
                Author: app/copilot-swe-agent (bot) · AI: agent (app/copilot-swe-agent) · Commits: alice (community), \
                bob (other: lookup limit)
                Report: reports/review-57720.md
                """), TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT)));
        assertTrue(MarkdownReport.render(outcome).contains("""
                ## CI checks (GitHub)

                unavailable

                ## Provenance (GitHub)

                Author: app/copilot-swe-agent (bot) · AI: agent (app/copilot-swe-agent) · Commits: alice (community), \
                bob (other: lookup limit)

                Read at 2026-11-18T10:15:00Z; GitHub's associations and memberships are as of then.

                ## Classification
                """), MarkdownReport.render(outcome));
    }

    @Test
    void aPrWithOnlyItsAuthorHasNoCommitsPart() {
        Provenance provenance = new Provenance(Person.of("brunobat", Who.TEAM), List.of(), AiLabel.NONE, READ_AT);
        PrOutcome outcome = withProvenance(notAnswered(null, "gh: x", "x"), provenance);

        assertTrue(TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT))
                .contains("\nAuthor: brunobat (team) · AI: none declared\nReport: "));
        assertTrue(MarkdownReport.render(outcome).contains("""
                ## Provenance (GitHub)

                Author: brunobat (team) · AI: none declared

                Read at 2026-11-18T10:15:00Z; GitHub's associations and memberships are as of then.

                ## Not classified
                """), MarkdownReport.render(outcome));
    }

    @Test
    void textInTheAuthorLineCannotChangeEitherOutput() {
        String hostile = "x|y <!-- `z`\n## Injected \u001b[2J[l](https://e.vil) www.e.vil @u";
        Provenance provenance = new Provenance(Person.other(hostile, hostile), List.of(Person.of(hostile, Who.TEAM)),
                new AiLabel(AiLabel.Kind.DECLARED, hostile), READ_AT);
        PrOutcome outcome = withProvenance(notAnswered(null, "gh: x", "x"), provenance);

        String terminal = TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT));
        String line = terminal.lines().filter(l -> l.startsWith("Author: ")).findFirst().orElseThrow();
        assertFalse(terminal.contains("\u001b"), terminal);
        assertFalse(terminal.contains("\n## Injected"), terminal);
        assertTrue(line.contains("x|y <!-- `z` ## Injected [2J[l](https://e.vil)"), line);

        String md = MarkdownReport.render(outcome);
        String mdLine = md.lines().filter(l -> l.startsWith("Author: ")).findFirst().orElseThrow();
        assertFalse(md.contains("\n## Injected"), md);
        assertFalse(mdLine.matches(".*[^\\\\]<.*"), mdLine);
        assertFalse(md.contains("\u001b"), md);
        assertFalse(mdLine.contains("](https://"), mdLine);
        assertFalse(mdLine.contains("www.e"), mdLine);
        assertTrue(mdLine.startsWith("Author: x\\|y \\<!-- \\`z\\` ## Injected"), mdLine);
    }

    static PrOutcome withCi(SourceResult<CiChecks> ci) {
        PrOutcome outcome = PrOutcome.notAnswered(57715, List.of("test-adequacy"), null, "gh: x", "x", null, null, null, null);
        return new PrOutcome(outcome.number(), outcome.checks(), null, outcome.jev(), outcome.size(), ci,
                outcome.provenance());
    }

    static CiChecks ci(List<CiCheck> checks) {
        return ci(checks, Approval.NOT_WAITING);
    }

    static CiChecks ci(List<CiCheck> checks, Approval approval) {
        return new CiChecks("ba9690af", checks, checks.stream().filter(c -> !List.of("SUCCESS", "SKIPPED", "NEUTRAL")
                .contains(c.value())).map(c -> new FailedCheck(c, List.of(FailureRow.withoutTest("details unavailable"))))
                .toList(), approval, List.of());
    }

    @Test
    void ciCountLineCoversEveryCase() {
        CiCheck ok = new CiCheck(CiCheck.Kind.CHECK_RUN, "a", "W", "SUCCESS", "");
        CiCheck neutral = new CiCheck(CiCheck.Kind.CHECK_RUN, "Build scans", "", "NEUTRAL", "");
        Map<SourceResult<CiChecks>, String[]> cases = new LinkedHashMap<>();
        cases.put(SourceResult.unavailable("gh: x"), new String[] { "CI: unavailable\n", "unavailable\n" });
        cases.put(new SourceResult.Available<>(ci(List.of())), new String[] {
                "CI: no workflow runs on the head commit\n", "No workflow runs on the head commit.\n" });
        cases.put(new SourceResult.Available<>(ci(List.of(ok, neutral))), new String[] {
                "CI: all 2 passed, skipped or neutral\n", "All 2 passed, skipped or neutral.\n" });
        cases.put(new SourceResult.Available<>(ci(List.of(ok, neutral), Approval.WAITING)), new String[] {
                "CI: workflows waiting for maintainer approval; only 2 checks ran (all 2 passed, skipped or neutral)\n",
                "Workflows waiting for maintainer approval; only 2 checks ran (all 2 passed, skipped or neutral).\n" });
        cases.put(new SourceResult.Available<>(ci(List.of(), Approval.WAITING)), new String[] {
                "CI: workflows waiting for maintainer approval; no checks ran\n",
                "Workflows waiting for maintainer approval; no checks ran.\n" });
        cases.put(new SourceResult.Available<>(ci(List.of(ok, neutral), Approval.UNAVAILABLE)), new String[] {
                "CI: all 2 passed, skipped or neutral (approval status unavailable)\n",
                "All 2 passed, skipped or neutral (approval status unavailable).\n" });
        cases.put(new SourceResult.Available<>(ci(List.of(), Approval.UNAVAILABLE)), new String[] {
                "CI: no workflow runs on the head commit (approval status unavailable)\n",
                "No workflow runs on the head commit (approval status unavailable).\n" });
        cases.forEach((ci, expected) -> {
            PrOutcome outcome = withCi(ci);
            assertTrue(TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT)).contains(
                    "Size: unavailable\n" + expected[0] + "Author: unavailable\nReport: "), expected[0]);
            assertTrue(MarkdownReport.render(outcome).contains("## CI checks (GitHub)\n\n" + expected[1]
                    + "\n## Provenance (GitHub)"), MarkdownReport.render(outcome));
        });
    }

    static final String LARGE = "io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest";
    static final String MODULE = "extensions/resteasy-reactive/rest-jackson/deployment";
    static final List<FlakyTest> FLAKY = List.of(
            new FlakyTest("JVM Tests - JDK 21", "Quarkus CI", LARGE, MODULE, "https://ge.quarkus.io/h1"),
            new FlakyTest("JVM Tests - JDK 25", "Quarkus CI", LARGE, MODULE, "https://ge.quarkus.io/h2"),
            new FlakyTest("JVM Tests - JDK 21 Windows", "Quarkus CI", LARGE, MODULE, "https://ge.quarkus.io/h3"),
            new FlakyTest("JVM Tests - JDK 21 Windows", "Quarkus CI",
                    "io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test", "extensions/vertx-http/deployment",
                    "https://ge.quarkus.io/h4"));

    static PrOutcome withFlaky(List<CiCheck> checks, List<FlakyTest> flaky) {
        CiChecks base = ci(checks);
        return withCi(new SourceResult.Available<>(new CiChecks(base.headCommit(), base.checks(), base.notPassed(),
                base.approval(), flaky)));
    }

    @Test
    void flakyTestsFollowTheCountLineOfAGreenPr() {
        PrOutcome outcome = withFlaky(List.of(new CiCheck(CiCheck.Kind.CHECK_RUN, "a", "W", "SUCCESS", "")), FLAKY);

        assertTrue(TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT)).contains("""
                CI: all 1 passed, skipped or neutral
                Flaky: 4 tests in 3 jobs (passed on retry)
                  Job                                      Test
                  JVM Tests - JDK 21 (Quarkus CI)          io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest
                  JVM Tests - JDK 25 (Quarkus CI)          io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest
                  JVM Tests - JDK 21 Windows (Quarkus CI)  io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest
                  JVM Tests - JDK 21 Windows (Quarkus CI)  io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test
                Author: unavailable
                """), TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT)));
        assertTrue(MarkdownReport.render(outcome).contains("""
                ## CI checks (GitHub)

                All 1 passed, skipped or neutral.

                Flaky: 4 tests in 3 jobs (passed on retry).

                | Job | Test | Module |
                |---|---|---|
                | JVM Tests - JDK 21 (Quarkus CI) | [`io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest`](https://ge.quarkus.io/h1) | extensions/resteasy-reactive/rest-jackson/deployment |
                """), MarkdownReport.render(outcome));
        assertTrue(MarkdownReport.render(outcome).contains("| extensions/vertx-http/deployment |\n\n## Provenance"),
                MarkdownReport.render(outcome));
    }

    @Test
    void flakyTestsFollowTheUnchangedFailureTable() {
        List<CiCheck> checks = List.of(new CiCheck(CiCheck.Kind.CHECK_RUN, "JVM Tests", "W", "FAILURE",
                "https://github.com/q/q/runs/2"));
        String before = TerminalRenderer.render(withFlaky(checks, List.of()), new ReportWriter.Written(REPORT));
        String beforeReport = MarkdownReport.render(withFlaky(checks, List.of()));
        PrOutcome outcome = withFlaky(checks, FLAKY.subList(0, 1));

        String block = TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT));
        String table = "  JVM Tests (W)  FAILURE               details unavailable\n";
        assertTrue(before.contains(table + "Author: "), before);
        assertTrue(block.contains(table + "Flaky: 1 test in 1 job (passed on retry)\n  Job"), block);
        String report = MarkdownReport.render(outcome);
        String failureTable = beforeReport.substring(beforeReport.indexOf("| Check |"),
                beforeReport.indexOf("\n\n## Provenance"));
        assertTrue(report.contains(failureTable + "\n\nFlaky: 1 test in 1 job (passed on retry).\n\n| Job | Test |"
                + " Module |\n"), report);
    }

    @Test
    void flakyLineWhenThereAreNoneTheCommentsAreUnreadableOrCiIsUnavailable() {
        List<CiCheck> green = List.of(new CiCheck(CiCheck.Kind.CHECK_RUN, "a", "W", "SUCCESS", ""));

        PrOutcome none = withFlaky(green, List.of());
        assertFalse(TerminalRenderer.render(none, new ReportWriter.Written(REPORT)).contains("Flaky:"));
        assertFalse(MarkdownReport.render(none).contains("Flaky:"));
        PrOutcome unreadable = withFlaky(green, null);
        assertTrue(TerminalRenderer.render(unreadable, new ReportWriter.Written(REPORT))
                .contains("CI: all 1 passed, skipped or neutral\nFlaky: unavailable\nAuthor: "));
        assertTrue(MarkdownReport.render(unreadable).contains(
                "All 1 passed, skipped or neutral.\n\nFlaky: unavailable.\n\n## Provenance"),
                MarkdownReport.render(unreadable));
        PrOutcome noCi = withCi(SourceResult.unavailable("gh: x"));
        assertFalse(TerminalRenderer.render(noCi, new ReportWriter.Written(REPORT)).contains("Flaky"));
        assertFalse(MarkdownReport.render(noCi).contains("Flaky"));
    }

    @Test
    void flakyValuesFromGitHubAreEscaped() {
        PrOutcome outcome = withFlaky(List.of(), List.of(new FlakyTest("Job | <b>\nx", "W", "a.B`c<d", "m|<!--",
                "javascript:alert(1)")));

        String block = TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT));
        assertTrue(block.contains("  Job | <b> x (W)  a.B`c<d\n"), block);
        String report = MarkdownReport.render(outcome);
        assertTrue(report.contains("| Job \\| \\<b\\> x (W) | `` a.B`c<d `` | m\\|\\<!-- |\n"), report);
    }

    @Test
    void waitingWithAFailedCheckKeepsTheTable() {
        List<CiCheck> checks = List.of(new CiCheck(CiCheck.Kind.CHECK_RUN, "a", "W", "SUCCESS", ""),
                new CiCheck(CiCheck.Kind.CHECK_RUN, "b", "W", "SUCCESS", ""),
                new CiCheck(CiCheck.Kind.CHECK_RUN, "JVM Tests", "W", "FAILURE", "https://github.com/q/q/runs/2"));
        PrOutcome outcome = withCi(new SourceResult.Available<>(ci(checks, Approval.WAITING)));

        String block = TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT));
        assertTrue(block.contains("CI: workflows waiting for maintainer approval; only 3 checks ran (1 of 3 not passed)\n"
                + "  Check"), block);
        assertTrue(block.contains("  JVM Tests (W)  FAILURE"), block);
        String report = MarkdownReport.render(outcome);
        assertTrue(report.contains("## CI checks (GitHub)\n\nWorkflows waiting for maintainer approval; only 3 checks ran"
                + " (1 of 3 not passed).\n\n| Check |"), report);
        assertTrue(outcome.waitingForApproval());
        assertFalse(withCi(new SourceResult.Available<>(ci(checks, Approval.UNAVAILABLE))).waitingForApproval());
        assertFalse(withCi(SourceResult.unavailable("gh: x")).waitingForApproval());
    }

    @Test
    void notPassedChecksAreATableWithLinksOnlyInMarkdown() {
        List<CiCheck> checks = List.of(
                new CiCheck(CiCheck.Kind.CHECK_RUN, "Initial JDK 17 Build", "Quarkus CI", "SUCCESS",
                        "https://github.com/q/q/actions/runs/1/job/1"),
                new CiCheck(CiCheck.Kind.CHECK_RUN, "JVM Tests - JDK 17", "Quarkus CI", "FAILURE",
                        "https://github.com/q/q/actions/runs/1/job/2"),
                new CiCheck(CiCheck.Kind.CHECK_RUN, "Native Tests", "Quarkus CI", "IN_PROGRESS",
                        "javascript:alert(1)"),
                new CiCheck(CiCheck.Kind.CHECK_RUN, "Odd [x] | y", "", "CANCELLED", "https://github.com/q/q/runs/3"),
                new CiCheck(CiCheck.Kind.STATUS, "CI Merge Gate", "", "FAILURE", ""));
        PrOutcome outcome = withCi(new SourceResult.Available<>(ci(checks)));

        assertEquals("""
                PR #57715 - test adequacy
                  not classified: gh: x
                Size: unavailable
                CI: 4 of 5 not passed
                  Check                            Result       Test  Flaky  What failed
                  JVM Tests - JDK 17 (Quarkus CI)  FAILURE                   details unavailable
                  Native Tests (Quarkus CI)        IN_PROGRESS               details unavailable
                  Odd [x] | y                      CANCELLED                 details unavailable
                  CI Merge Gate (status)           FAILURE                   details unavailable
                Author: unavailable
                Report: reports/review-57720.md
                """, TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT)));
        assertTrue(MarkdownReport.render(outcome).contains("""
                ## CI checks (GitHub)

                4 of 5 not passed.

                | Check | Result | Test | Flaky | What failed |
                |---|---|---|---|---|
                | [JVM Tests - JDK 17](https://github.com/q/q/actions/runs/1/job/2) (Quarkus CI) | `FAILURE` |  |  | details unavailable |
                | Native Tests (Quarkus CI) | `IN_PROGRESS` |  |  | details unavailable |
                | [Odd \\[x\\] \\| y](https://github.com/q/q/runs/3) | `CANCELLED` |  |  | details unavailable |
                | CI Merge Gate (status) | `FAILURE` |  |  | details unavailable |

                ## Provenance (GitHub)
                """), MarkdownReport.render(outcome));
    }

    static PrOutcome withRows(List<FailureRow> rows) {
        CiCheck check = new CiCheck(CiCheck.Kind.CHECK_RUN, "JVM Tests - JDK 21", "Quarkus CI", "FAILURE",
                "https://github.com/q/q/actions/runs/1/job/2");
        return withCi(new SourceResult.Available<>(new CiChecks("ba9690af", List.of(check),
                List.of(new FailedCheck(check, rows)), Approval.NOT_WAITING, List.of())));
    }

    @Test
    void failingTestsShowTheirNameFlakyAndErrorWithLinksOnlyInMarkdown() {
        PrOutcome outcome = withRows(List.of(
                new FailureRow("io.q.MaxFrameSizeTest.testMaxFrameSize", false,
                        "java.util.concurrent.CompletionException",
                        "https://develocity.quarkus.io/scans/tests?tests.test=testMaxFrameSize",
                        "https://develocity.quarkus.io/s/hzvs2"),
                new FailureRow("io.q.GracefulShutdownFilterTest.test", true,
                        "Timeout waiting for response - java.lang.RuntimeException",
                        "https://develocity.quarkus.io/scans/tests?tests.test=test", null),
                new FailureRow(null, null, "Failed to execute goal surefire on project x: See [date].dump", null,
                        null)));

        assertTrue(TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT)).contains("""
                CI: 1 of 1 not passed
                  Check                            Result   Test                                    Flaky  What failed
                  JVM Tests - JDK 21 (Quarkus CI)  FAILURE  io.q.MaxFrameSizeTest.testMaxFrameSize  no     \
                java.util.concurrent.CompletionException
                  JVM Tests - JDK 21 (Quarkus CI)  FAILURE  io.q.GracefulShutdownFilterTest.test    yes    \
                Timeout waiting for response - java.lang.RuntimeException
                  JVM Tests - JDK 21 (Quarkus CI)  FAILURE                                                 \
                Failed to execute goal surefire on project x: See [date].dump
                Author: unavailable
                Report:"""), TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT)));
        String md = MarkdownReport.render(outcome);
        assertTrue(md.contains("""
                | [JVM Tests - JDK 21](https://github.com/q/q/actions/runs/1/job/2) (Quarkus CI) | `FAILURE` | \
                [`io.q.MaxFrameSizeTest.testMaxFrameSize`](https://develocity.quarkus.io/scans/tests?tests.test=testMaxFrameSize) \
                | no | java.util.concurrent.CompletionException · [scan](https://develocity.quarkus.io/s/hzvs2) |
                | [JVM Tests - JDK 21](https://github.com/q/q/actions/runs/1/job/2) (Quarkus CI) | `FAILURE` | \
                [`io.q.GracefulShutdownFilterTest.test`](https://develocity.quarkus.io/scans/tests?tests.test=test) \
                | yes | Timeout waiting for response - java.lang.RuntimeException |
                | [JVM Tests - JDK 21](https://github.com/q/q/actions/runs/1/job/2) (Quarkus CI) | `FAILURE` |  |  | \
                Failed to execute goal surefire on project x: See \\[date\\].dump |
                """), md);
    }

    @Test
    void textFromABotCommentCannotChangeTheTableOrAddLinks() {
        PrOutcome outcome = withRows(List.of(new FailureRow("a.B | c\n## x", true,
                "<script>x</script> | [click](https://evil.example)\n# heading", "javascript:alert(1)",
                "https://ok.example/s (x)")));

        String md = MarkdownReport.render(outcome);
        String row = md.lines().filter(l -> l.startsWith("| [JVM Tests")).findFirst().orElseThrow();
        assertEquals("| [JVM Tests - JDK 21](https://github.com/q/q/actions/runs/1/job/2) (Quarkus CI) | `FAILURE` | "
                + "`a.B \\| c ## x` | yes | \\<script\\>x\\</script\\> \\| \\[click\\](https\\://evil.example) # heading |",
                row);
        assertEquals(6, row.replace("\\|", "").chars().filter(ch -> ch == '|').count(), row);
        assertFalse(md.contains("javascript:"), md);
        assertFalse(md.contains("ok.example"), md);
        assertFalse(md.lines().anyMatch(l -> l.startsWith("# heading") || l.startsWith("## x")), md);
    }

    @Test
    void escapesInBotTextCannotBeUndoneAndNothingInItIsLinked() {
        String error = "\\[Approve\\](https://evil.example/x) \\[a\\](`)<b>x</b> ` see https://evil.example/y, "
                + "WWW.evil.example and ops@evil.example *bold* _it_ ~~gone~~ &amp; \\";
        PrOutcome outcome = withRows(List.of(new FailureRow("a.B", false, error, null, null)));

        String row = MarkdownReport.render(outcome).lines().filter(l -> l.startsWith("| [JVM Tests")).findFirst()
                .orElseThrow();
        String whatFailed = row.substring(row.indexOf("| no | ") + "| no | ".length(), row.length() - " |".length());
        assertEquals("\\\\\\[Approve\\\\\\](https\\://evil.example/x) \\\\\\[a\\\\\\](\\`)\\<b\\>x\\</b\\> \\` "
                + "see https\\://evil.example/y, WWW\\.evil.example and ops@&#8203;evil.example \\*bold\\* \\_it\\_ "
                + "\\~\\~gone\\~\\~ \\&amp; \\\\", whatFailed);
    }

    @Test
    void controlCharactersInBotTextReachNeitherOutput() {
        String error = "\u001b[2Kgone\u0007 \u009b31m\u202Eevil\u0000 ok";
        PrOutcome outcome = withRows(List.of(new FailureRow("a.B\u001b]8;;x", false, error, null, null)));

        String terminal = TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT));
        String md = MarkdownReport.render(outcome);

        for (String out : List.of(terminal, md)) {
            assertFalse(out.chars().anyMatch(c -> c != '\n' && (Character.isISOControl(c) || c == 0x202E)), out);
        }
        assertTrue(terminal.contains("[2Kgone 31mevil ok"), terminal);
        assertTrue(md.contains("\\[2Kgone 31mevil ok"), md);
    }

    @Test
    void aLongFailureIsOneLineOfAtMost120CharactersInTheTerminal() {
        String error = "x".repeat(150) + "\n" + "y".repeat(150);
        PrOutcome outcome = withRows(List.of(new FailureRow("a.B.c", false, error, null, null)));

        String line = TerminalRenderer.render(outcome, new ReportWriter.Written(REPORT)).lines()
                .filter(l -> l.contains("a.B.c")).findFirst().orElseThrow();
        String whatFailed = line.substring(line.indexOf("x"));
        assertEquals("x".repeat(119) + "\u2026", whatFailed);
        assertFalse(line.contains("https://"), line);
    }
}
