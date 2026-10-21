package io.quarkus.prreview.pipelines.services;


import io.quarkus.prreview.pipelines.model.DeepResult;
import io.quarkus.prreview.pipelines.model.TriageOutcome;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.prreview.facts.model.Facts;
import io.quarkus.prreview.facts.model.TestFacts;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.model.Band;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The test-adequacy deep pipeline — zero-tests confirmation pass (plan §7 test-adequacy). Escalation-only: it resolves the
 * ambiguous middle triage could not settle. Deterministic gates run first (no jev call when facts already
 * decide), then the four deep questions confirm the vacuous/weak cases with full test bodies in
 * view. The committer asymmetry is a pinned fact applied here, never a jev question (I-2/I-3).
 */
@ApplicationScoped
public class TestAdequacyPipeline implements DeepPipeline {

    private static final String CONCERN = "test-adequacy";
    private static final String QUESTIONS = "test-adequacy";

    @Inject
    DeepSupport support;

    @Override
    public String concern() {
        return CONCERN;
    }

    @Override
    public Uni<DeepResult> run(Facts facts, TriageOutcome triage) {
        // Step 1: nothing to verify — deterministic silence, no jev call.
        if (!facts.hasRuntimeChange() || facts.changeClassification().allRuntimeExempt()) {
            return Uni.createFrom().item(DeepResult.skipped(CONCERN, "no runtime change / all exempt"));
        }
        // Step 2: zero tests / all disabled / none asserting — deterministic hard finding, no jev call.
        if (!hasEnabledAssertingTest(facts)) {
            return Uni.createFrom().item(
                    DeepResult.ok(CONCERN, List.of(missingTestFinding(facts)), Map.of()));
        }
        // Steps 3–5: tests-with-assertions exist — confirm adequacy via jev.
        ObjectNode state = buildState(facts, triage);
        return support.ask(QUESTIONS, state)
                .map(answers -> DeepResult.ok(CONCERN, interpret(answers, facts), answers))
                .onFailure().recoverWithItem(t -> DeepResult.errored(CONCERN, String.valueOf(t.getMessage())));
    }

    ObjectNode buildState(Facts facts, TriageOutcome triage) {
        ObjectNode root = support.mapper().createObjectNode();
        root.set("runtime_files", StateNodes.files(support.mapper(), facts.runtimeFiles()));
        root.set("tests", StateNodes.tests(support.mapper(), facts.tests()));
        // Triage's own test_situation/test_adequacy as a neutral prior to confirm or overturn (§7 test-adequacy STATE).
        ObjectNode prior = root.putObject("triage_prior");
        putPrior(prior, "test_situation", triage);
        putPrior(prior, "test_adequacy", triage);
        return root;
    }

    private static void putPrior(ObjectNode prior, String id, TriageOutcome triage) {
        BandedAnswer a = triage.answers().get(id);
        if (a == null) {
            return;
        }
        ObjectNode n = prior.putObject(id);
        n.put("band", a.band().name());
        if (a.choice() != null) {
            n.put("choice", a.choice());
        }
        if (!Double.isNaN(a.score())) {
            n.put("score", a.scoreInt());
        }
    }

    /** §7 test-adequacy interpretation steps 3–5, pure over banded answers + the pinned committer fact. */
    List<Finding> interpret(Map<String, BandedAnswer> answers, Facts facts) {
        Severity hard = Verdicts.byCommitter(facts);
        BandedAnswer exercisesPath = answers.get("test_exercises_changed_path");
        BandedAnswer assertsBehavior = answers.get("accompanying_test_asserts_behavior");
        BandedAnswer allVacuous = answers.get("all_tests_vacuous");
        BandedAnswer coverage = answers.get("case_coverage");

        List<Finding> candidates = new ArrayList<>();

        // Step 3: all assertions collectively vacuous ≡ zero tests.
        if (allVacuous != null && allVacuous.isConfirmYes()) {
            candidates.add(finding(hard, "test-vacuous", false));
        } else if (allVacuous != null && allVacuous.band() == Band.FLAG) {
            candidates.add(finding(Severity.NEEDS_ATTENTION, "test-possibly-vacuous", true));
        }

        // Confident "no test exercises the path" / "no assertion checks the change" corroborate vacuity.
        if (exercisesPath != null && exercisesPath.isConfirmNo()) {
            candidates.add(finding(hard, "test-missing", false));
        }
        if (assertsBehavior != null && assertsBehavior.isConfirmNo()) {
            candidates.add(finding(hard, "test-vacuous", false));
        }

        // Step 4: grade by the case_coverage score.
        int coverageScore = coverage == null ? 0 : coverage.scoreInt();
        boolean coverageConfirm = coverage != null && coverage.isConfirm();
        if (coverage != null) {
            if (coverageScore == 0) {
                candidates.add(finding(hard, "test-vacuous", !coverageConfirm));
            } else if (coverageScore == 1) {
                candidates.add(finding(Severity.NEEDS_ATTENTION, "test-adequacy-weak", !coverageConfirm));
            } else if (coverageScore >= 2 && !coverageConfirm) {
                // Step 5: adequate-looking but sub-CONFIRM — do not clear; flag for a human.
                candidates.add(finding(Severity.NEEDS_ATTENTION, "test-adequacy-weak", true));
            }
            // case_coverage >= 2 at CONFIRM contributes nothing → silence.
        }

        return Verdicts.mostSevere(candidates);
    }

    private Finding missingTestFinding(Facts facts) {
        boolean allDisabled = !facts.tests().isEmpty() && facts.tests().stream().allMatch(TestFacts::disabled);
        String key = allDisabled ? "test-disabled" : "test-missing";
        return finding(Verdicts.byCommitter(facts), key, false);
    }

    private Finding finding(Severity severity, String messageKey, boolean tentative) {
        return Finding.of(severity, CONCERN, messageKey, support.summary(messageKey))
                .tentative(tentative)
                .withRationale("pipeline:test-adequacy");
    }

    private boolean hasEnabledAssertingTest(Facts facts) {
        return facts.tests().stream().anyMatch(t -> !t.disabled() && t.hasAssertion());
    }
}
