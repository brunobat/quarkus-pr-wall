package io.quarkus.prreview.evals;

import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.review.model.PipelineRun;
import io.quarkus.prreview.review.model.ReviewResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The eval suite (plan §10, §13). Every case in {@code evals/cases.txt} is run through the real review
 * path and its structured outcome asserted — severity, concern and message key, never rendered prose, so
 * the suite is immune to model wording drift.
 *
 * <p>Each case is a separate JUnit dynamic test, so a regression names the case that broke rather than
 * failing one opaque aggregate.
 */
@QuarkusTest
@TestProfile(EvalProfile.class)
class EvalSuiteTest {

    @Inject
    EvalRunner runner;

    @AfterEach
    void clearFixtures() {
        EvalRunner.reset();
    }

    @TestFactory
    List<DynamicTest> evalCorpus() {
        return EvalCases.all().stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> check(c)))
                .toList();
    }

    private void check(EvalCase c) {
        ReviewResult result = runner.run(c);

        assertFalse(result.isErrored(), () -> c.name() + " errored: " + result.note());
        assertEquals(c.pr().number(), result.prNumber());

        EvalCase.Expect expect = c.expect();
        if (expect.priority() != null) {
            assertEquals(expect.priority().intValue(), result.priority(),
                    () -> describe(c, result, "review priority"));
        }
        if (expect.minBlastRadius() != null) {
            assertTrue(result.blastRadius() >= expect.minBlastRadius(),
                    () -> describe(c, result, "blast radius >= " + expect.minBlastRadius()));
        }
        if (expect.pipelinesRun() != null) {
            assertEquals(expect.pipelinesRun(),
                    result.pipelines().stream().map(PipelineRun::id).toList(),
                    () -> describe(c, result, "deep pipelines run"));
        }
        if (c.clean()) {
            assertTrue(result.findings().isEmpty(), () -> describe(c, result, "no findings (clean case)"));
        }

        for (EvalCase.ExpectedFinding wanted : expect.findings()) {
            assertMatch(c, result, wanted);
        }
        for (String concern : expect.absentConcerns()) {
            assertTrue(byConcern(result, concern).isEmpty(),
                    () -> describe(c, result, "no finding for concern '" + concern + "'"));
        }
        if (expect.exactly()) {
            Set<String> actual = result.findings().stream().map(Finding::concern).collect(Collectors.toSet());
            Set<String> wanted = expect.findings().stream()
                    .map(EvalCase.ExpectedFinding::concern).collect(Collectors.toSet());
            assertEquals(wanted, actual, () -> describe(c, result, "exactly these concerns"));
        }
    }

    private void assertMatch(EvalCase c, ReviewResult result, EvalCase.ExpectedFinding wanted) {
        Optional<Finding> found = byConcern(result, wanted.concern());
        assertTrue(found.isPresent(), () -> describe(c, result, "a finding for '" + wanted.concern() + "'"));

        Finding f = found.get();
        assertEquals(Severity.fromString(wanted.severity()), f.severity(),
                () -> describe(c, result, wanted.concern() + " severity"));
        if (wanted.messageKey() != null) {
            assertEquals(wanted.messageKey(), f.messageKey(),
                    () -> describe(c, result, wanted.concern() + " message key"));
        }
        if (wanted.tentative() != null) {
            assertEquals(wanted.tentative(), f.tentative(),
                    () -> describe(c, result, wanted.concern() + " tentative flag"));
        }
    }

    private static Optional<Finding> byConcern(ReviewResult result, String concern) {
        return result.findings().stream().filter(f -> f.concern().equals(concern)).findFirst();
    }

    /** A failure message that shows the whole actual outcome — otherwise re-deriving it costs a rerun. */
    static String describe(EvalCase c, ReviewResult result, String expectation) {
        StringBuilder sb = new StringBuilder("eval '").append(c.name()).append("' — expected ")
                .append(expectation).append("\n  priority=").append(result.priority())
                .append(" blastRadius=").append(result.blastRadius())
                .append("\n  pipelines=");
        result.pipelines().forEach(p -> sb.append(p.id()).append(':').append(p.status())
                .append(p.note() == null ? "" : "(" + p.note() + ")").append(' '));
        sb.append("\n  findings=");
        if (result.findings().isEmpty()) {
            sb.append("(none)");
        }
        for (Finding f : result.findings()) {
            sb.append("\n    ").append(f.severity()).append(' ').append(f.concern())
                    .append(" key=").append(f.messageKey())
                    .append(f.tentative() ? " (tentative)" : "")
                    .append(" rationale=").append(f.rationale());
        }
        return sb.toString();
    }
}
