package io.quarkus.prreview.evals;

import io.quarkus.prreview.review.model.AuthorContext;
import io.quarkus.prreview.review.model.AuthorKind;
import io.quarkus.prreview.review.model.ReviewResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The author bucket the report shows, asserted end to end over the real eval fixtures rather than over
 * a hand-built {@link AuthorContext}.
 *
 * <p>It exists because the failure it catches is a gap, not a bug: {@code hasHumanCommitAuthor} was
 * computed correctly in the facts layer for a while and simply never forwarded to the report, so every
 * unit test around it passed while the column it feeds could not have worked. A test that constructs
 * the record itself would have passed too. This one runs the engine.
 */
@QuarkusTest
@TestProfile(EvalProfile.class)
class AuthorKindTest {

    @Inject
    EvalRunner runner;

    @AfterEach
    void clearFixtures() {
        EvalRunner.reset();
    }

    private AuthorContext authorOf(String caseName) {
        EvalCase c = EvalCases.all().stream()
                .filter(e -> e.name().equals(caseName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no eval case named '" + caseName + "'"));
        ReviewResult r = runner.run(c);
        assertFalse(r.isErrored(), () -> caseName + " errored: " + r.note());
        return r.authorContext();
    }

    @Test
    void routineAutomationIsABot() {
        AuthorContext a = authorOf("clean-dependabot-version-bump");

        assertEquals(AuthorKind.BOT, a.kind());
        assertFalse(a.hasHumanCommitAuthor(), "dependabot authored its own commit");
    }

    /**
     * The split that makes the column worth having: both of these are bot accounts to GitHub, and only
     * the allowlist separates a dependency bump from a substantive agent-written change.
     */
    @Test
    void aCodingAgentIsNotLumpedInWithRoutineAutomation() {
        AuthorContext a = authorOf("coding-agent-runtime-change-no-test");

        assertEquals(AuthorKind.AGENT, a.kind());
        assertTrue(a.authorIsBot());
        assertFalse(a.authorIsAllowlistedBot());
        assertFalse(a.hasHumanCommitAuthor(),
                "the agent authored the commit itself — reporting a human commit here would be a lie");
    }

    /** Everything else is a human account, and the absence of a declaration keeps it in the residual. */
    @Test
    void anOrdinaryContributorIsTheResidualHumanBucket() {
        AuthorContext a = authorOf("runtime-change-no-test");

        assertEquals(AuthorKind.HUMAN, a.kind());
        assertFalse(a.declaresAiAssistance());
    }

    /**
     * The forwarding this test was written for. The case's commits are the agent's, so flipping the
     * fixture's commit author to a person must move the fact all the way to the report — a bot that
     * merely delivered a human's work.
     */
    @Test
    void aHumanCommitUnderAnAgentAccountReachesTheReport() {
        EvalCase c = EvalCases.all().stream()
                .filter(e -> e.name().equals("coding-agent-runtime-change-no-test"))
                .findFirst()
                .orElseThrow();
        EvalCase.Pr pr = c.pr();
        EvalCase.Commit original = pr.commits().get(0);
        EvalCase.Commit humanAuthored = new EvalCase.Commit(
                original.oid(), original.messageHeadline(), original.messageBody(),
                original.authoredDate(), original.committedDate(), original.parentCount(),
                "Alice Contributor", "alice", original.committerName(), original.committerLogin());
        EvalCase.Pr delivered = new EvalCase.Pr(
                pr.number(), pr.title(), pr.body(), pr.labels(), pr.author(), pr.authorIsBot(),
                pr.baseBranch(), pr.headBranch(), pr.baseSha(), pr.headSha(), List.of(humanAuthored));

        ReviewResult r = runner.run(new EvalCase(c.name(), c.description(), c.clean(), c.fullDepth(),
                delivered, c.author(), c.diffFile(), c.answers(), c.expect()));

        assertFalse(r.isErrored(), () -> "errored: " + r.note());
        assertEquals(AuthorKind.AGENT, r.authorContext().kind(), "the account type is unchanged");
        assertTrue(r.authorContext().hasHumanCommitAuthor(),
                "the fact is computed in the facts layer; this asserts it survives the trip to the report");
    }
}
