package io.quarkus.prreview.pipelines.services;


import io.quarkus.prreview.pipelines.model.DeepResult;
import io.quarkus.prreview.pipelines.model.TriageOutcome;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.prreview.facts.model.Facts;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * The test-weakening deep pipeline — test/assertion weakening alongside a behavior change (plan
 * §7 test-weakening). Only relevant when the PR
 * actually touches an existing test (a deterministic fact); otherwise there is nothing to weaken. The
 * guarded_security_or_regression signal only raises report emphasis — it never changes the severity
 * by itself.
 */
@ApplicationScoped
public class TestWeakeningPipeline implements DeepPipeline {

    private static final String CONCERN = "test-weakening";
    private static final String QUESTIONS = "test-weakening";

    @Inject
    DeepSupport support;

    @Override
    public String concern() {
        return CONCERN;
    }

    @Override
    public Uni<DeepResult> run(Facts facts, TriageOutcome triage) {
        // Weakening requires existing test coverage to be edited/removed (deterministic fact).
        if (!facts.touchesExistingTest()) {
            return Uni.createFrom().item(DeepResult.skipped(CONCERN, "no existing test touched"));
        }
        ObjectNode state = buildState(facts);
        return support.ask(QUESTIONS, state)
                .map(answers -> DeepResult.ok(CONCERN, interpret(answers), answers))
                .onFailure().recoverWithItem(t -> DeepResult.errored(CONCERN, String.valueOf(t.getMessage())));
    }

    ObjectNode buildState(Facts facts) {
        ObjectNode root = support.mapper().createObjectNode();
        // Edited/removed existing test hunks + the runtime hunks in the same PR (plan §7 test-weakening STATE).
        root.set("test_files", StateNodes.files(support.mapper(), facts.testFiles()));
        root.set("runtime_files", StateNodes.files(support.mapper(), facts.runtimeFiles()));
        return root;
    }

    /** §7 test-weakening interpretation. */
    List<Finding> interpret(Map<String, BandedAnswer> answers) {
        BandedAnswer weakens = answers.get("weakens_existing_coverage");
        BandedAnswer withRuntimeChange = answers.get("coincides_with_runtime_change");
        BandedAnswer guardsSecurity = answers.get("guarded_security_or_regression");
        if (weakens == null || withRuntimeChange == null) {
            return List.of();
        }

        boolean weakensYes = weakens.isConfirmYes();
        boolean weakensRisk = weakensYes || (weakens.atLeastFlag() && !weakens.yes());
        boolean runtimeRisk = withRuntimeChange.isConfirmYes()
                || (withRuntimeChange.atLeastFlag() && !withRuntimeChange.yes());

        if (weakensYes && withRuntimeChange.isConfirmYes()) {
            return List.of(finding(Severity.BLOCKER, "tests-weakened", false, guardsSecurity));
        }
        if (weakensYes && withRuntimeChange.isConfirmNo()) {
            return List.of(finding(Severity.NEEDS_ATTENTION, "tests-weakened-refactor", false, guardsSecurity));
        }
        // Sub-CONFIRM on either while the other is present → FLAG; never cleared (I-5).
        if (weakensRisk && runtimeRisk) {
            return List.of(finding(Severity.NEEDS_ATTENTION, "tests-weakened", true, guardsSecurity));
        }
        return List.of();
    }

    private Finding finding(Severity severity, String messageKey, boolean tentative, BandedAnswer guardsSecurity) {
        String rationale = guardsSecurity != null && guardsSecurity.isConfirmYes()
                ? "pipeline:test-weakening; guards a security/regression path"
                : "pipeline:test-weakening";
        return Finding.of(severity, CONCERN, messageKey, support.summary(messageKey))
                .tentative(tentative)
                .withRationale(rationale);
    }
}
