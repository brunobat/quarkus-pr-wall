package io.quarkus.prreview.evals;

import io.quarkus.prreview.review.model.PipelineRun;
import io.quarkus.prreview.review.model.ReviewResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-7, as a measurement rather than a claim: declaring AI assistance changes nothing about the review.
 *
 * <p>Every case in the corpus is run twice from <em>one</em> in-memory fixture, the two runs differing in
 * exactly one character sequence — a {@code Co-Authored-By: Claude} trailer on the single commit. Both runs
 * carry that commit, so the commit count, the authorship and the human-author signal are held constant and
 * the declaration is the only free variable. Priority, blast radius, findings and the deep-pipeline set must
 * come back equal.
 *
 * <p>The last assertion is the one that keeps the rest honest. Asserting "nothing changed" passes trivially
 * if the trailer is never detected at all, so each pair also asserts that the declared run did resolve the
 * declaration and the undeclared run did not. Equality then means the signal was seen and deliberately not
 * acted on, which is the actual invariant.
 *
 * <p>This is a differential test rather than a lint because {@link
 * io.quarkus.prreview.interpret.rules.services.RulesetLint} can only see the ruleset. A future escalation
 * wired in Java — in {@code TriagePipeline}, a deep pipeline, or the combiner — would sail past the lint
 * and fail here.
 */
@QuarkusTest
@TestProfile(EvalProfile.class)
class DeclarationNeutralityTest {

    private static final String TRAILER = "Co-Authored-By: Claude <noreply@anthropic.com>";

    @Inject
    EvalRunner runner;

    @AfterEach
    void clearFixtures() {
        EvalRunner.reset();
    }

    @TestFactory
    List<DynamicTest> declaringChangesNothing() {
        return EvalCases.all().stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> compareTwins(c)))
                .toList();
    }

    private void compareTwins(EvalCase c) {
        ReviewResult silent = runner.run(withCommitBody(c, "Refs #1234"));
        ReviewResult declared = runner.run(withCommitBody(c, "Refs #1234\n\n" + TRAILER));

        assertFalse(silent.isErrored(), () -> c.name() + " (undeclared) errored: " + silent.note());
        assertFalse(declared.isErrored(), () -> c.name() + " (declared) errored: " + declared.note());

        assertEquals(silent.priority(), declared.priority(),
                () -> diff(c, "review priority", silent, declared));
        assertEquals(silent.blastRadius(), declared.blastRadius(),
                () -> diff(c, "blast radius", silent, declared));
        assertEquals(findings(silent), findings(declared),
                () -> diff(c, "findings", silent, declared));
        assertEquals(pipelines(silent), pipelines(declared),
                () -> diff(c, "deep pipelines run", silent, declared));

        // Without this the equality above could be satisfied by never reading the trailer in the first place.
        assertTrue(declared.authorContext().declaresAiAssistance(),
                () -> c.name() + ": the trailer was not detected, so the comparison above proves nothing");
        assertFalse(silent.authorContext().declaresAiAssistance(),
                () -> c.name() + ": the undeclared twin reported a declaration it does not carry");
    }

    /**
     * The same case with one commit whose body is the only thing that varies between twins. The commit is
     * attributed to the PR author so that {@code hasHumanCommitAuthor} and the commit count are identical
     * on both sides — only the trailer differs.
     */
    private static EvalCase withCommitBody(EvalCase c, String messageBody) {
        EvalCase.Pr pr = c.pr();
        EvalCase.Commit commit = new EvalCase.Commit(
                "0e7f1a2", pr.title(), messageBody, null, null, 1,
                pr.author(), pr.author(), pr.author(), pr.author());
        EvalCase.Pr twin = new EvalCase.Pr(
                pr.number(), pr.title(), pr.body(), pr.labels(), pr.author(), pr.authorIsBot(),
                pr.baseBranch(), pr.headBranch(), pr.baseSha(), pr.headSha(), List.of(commit));
        return new EvalCase(c.name(), c.description(), c.clean(), c.fullDepth(), twin, c.author(),
                c.diffFile(), c.answers(), c.expect());
    }

    /** Findings as comparable text — the IR fields the suite asserts on, never rendered prose. */
    private static List<String> findings(ReviewResult r) {
        return r.findings().stream()
                .map(f -> f.severity() + " " + f.concern() + " " + f.messageKey()
                        + (f.tentative() ? " (tentative)" : ""))
                .sorted()
                .toList();
    }

    private static List<String> pipelines(ReviewResult r) {
        return r.pipelines().stream().map(PipelineRun::concern).sorted().toList();
    }

    private static String diff(EvalCase c, String what, ReviewResult silent, ReviewResult declared) {
        return "eval '" + c.name() + "' — declaring AI assistance changed the " + what + " (I-7)"
                + "\n  undeclared: " + summarize(silent)
                + "\n  declared:   " + summarize(declared);
    }

    private static String summarize(ReviewResult r) {
        return "priority=" + r.priority() + " blast=" + r.blastRadius()
                + " pipelines=" + pipelines(r) + " findings=" + findings(r);
    }
}
