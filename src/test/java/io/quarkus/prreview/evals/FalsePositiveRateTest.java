package io.quarkus.prreview.evals;

import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.review.model.ReviewResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The false-positive-rate metric (plan §10): the headline number the thresholds in
 * {@code thresholds.yaml} are tuned against. A reviewer stops reading an agent that cries wolf, so the
 * design treats SILENCE as the default and this test as the guard on that default.
 *
 * <p>Scope and honesty about it: the corpus here is the {@code clean: true} cases in
 * {@code evals/cases.txt} — PRs that a human reviewer would wave through. Any emitted finding on one of
 * them is a false positive by construction. That makes this a real regression guard on the gate and the
 * rulesets, but <em>not</em> a calibration against the live distribution of quarkusio/quarkus PRs: the
 * shipped band values are still the plan's initial estimates, and fitting them needs a recorded corpus of
 * real PRs with human labels (plan §12 Phase 6, still open).
 */
@QuarkusTest
@TestProfile(EvalProfile.class)
class FalsePositiveRateTest {

    private static final Logger LOG = Logger.getLogger(FalsePositiveRateTest.class);

    /**
     * Zero. On a corpus this small a non-zero budget would just license one silent regression; when the
     * corpus grows to real recorded PRs this becomes a fitted rate rather than an absolute.
     */
    private static final double MAX_FALSE_POSITIVE_RATE = 0.0;

    @Inject
    EvalRunner runner;

    @AfterEach
    void clearFixtures() {
        EvalRunner.reset();
    }

    @Test
    void cleanPrsStaySilent() {
        List<EvalCase> corpus = EvalCases.all().stream().filter(EvalCase::clean).toList();
        assertTrue(corpus.size() >= 3,
                "the FP corpus is the only guard on the silence default — keep at least 3 clean cases");

        List<String> noise = new ArrayList<>();
        int blockers = 0;
        for (EvalCase c : corpus) {
            ReviewResult result = runner.run(c);
            assertTrue(!result.isErrored(), () -> c.name() + " errored: " + result.note());
            for (Finding f : result.findings()) {
                noise.add(c.name() + ": " + f.severity() + ' ' + f.concern() + " (" + f.messageKey() + ')');
                if (f.severity() == Severity.BLOCKER) {
                    blockers++;
                }
            }
        }

        long noisy = corpus.stream().filter(c -> noise.stream().anyMatch(n -> n.startsWith(c.name() + ':')))
                .count();
        double rate = (double) noisy / corpus.size();
        LOG.infof("false-positive rate: %.2f (%d/%d clean PRs emitted a finding; %d findings, %d blockers)",
                rate, noisy, corpus.size(), noise.size(), blockers);

        assertTrue(rate <= MAX_FALSE_POSITIVE_RATE,
                () -> String.format("false-positive rate %.2f exceeds the %.2f budget:%n  %s",
                        rate, MAX_FALSE_POSITIVE_RATE, String.join("\n  ", noise)));
    }

    /**
     * The FP corpus is only meaningful if the clean PRs actually reach the deep pipelines — a case that
     * triages straight to "nothing to look at" proves nothing about the gate. {@code clean-well-tested-fix}
     * is the one that must run the full fan-out and still clear.
     */
    @Test
    void theFullDepthCleanCaseExercisesEveryPipeline() {
        EvalCase c = EvalCases.all().stream()
                .filter(e -> e.name().equals("clean-well-tested-fix")).findFirst()
                .orElseThrow(() -> new AssertionError("clean-well-tested-fix is missing from the FP corpus"));

        ReviewResult result = runner.run(c);

        assertEquals(List.of("A", "B", "C", "D", "E", "F", "G"),
                result.pipelines().stream().map(p -> p.id()).sorted().toList(),
                () -> EvalSuiteTest.describe(c, result, "all seven deep pipelines to have run"));
        assertTrue(result.findings().isEmpty(),
                () -> EvalSuiteTest.describe(c, result, "silence from every pipeline"));
    }
}
