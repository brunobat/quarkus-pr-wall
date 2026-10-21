package io.quarkus.prreview.pipelines.services;


import io.quarkus.prreview.pipelines.model.TriageOutcome;
import io.quarkus.prreview.facts.model.Facts;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.services.ConfidenceGate;
import io.quarkus.prreview.interpret.services.FactView;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Thresholds;
import io.quarkus.prreview.interpret.rules.model.Decisions;
import io.quarkus.prreview.interpret.rules.services.MessageCatalog;
import io.quarkus.prreview.interpret.rules.services.RuleEngine;
import io.quarkus.prreview.interpret.rules.services.RulesetLoader;
import io.quarkus.prreview.interpret.rules.model.TuningConfig;
import io.quarkus.prreview.jev.model.JevResponse;
import io.quarkus.prreview.jev.services.JevService;
import io.quarkus.prreview.questions.services.QuestionRegistry;
import io.quarkus.prreview.questions.model.QuestionSet;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The triage pipeline (plan §5): one cheap {@code jev} call that is both the cascade gate (which deep
 * pipelines run) and the first-pass grader for the two load-bearing concerns (test adequacy, breaking
 * change). It is {@code buildState → ask → interpret}, with all policy applied by the pure
 * {@link RuleEngine} plus the compiled-in invariant tier.
 *
 * <p>Interpretation is deterministic and offline; only the one {@code jev} call touches the network.
 */
@ApplicationScoped
public class TriagePipeline {

    private static final String TEST_CONCERN = "test-adequacy";
    private static final String BREAKING_CONCERN = "breaking-change";
    private static final String PIPELINE_A = "A";
    private static final String PIPELINE_C = "C";

    @Inject
    JevService jev;

    @Inject
    QuestionRegistry questions;

    @Inject
    ConfidenceGate gate;

    @Inject
    RulesetLoader rulesets;

    @Inject
    MessageCatalog messages;

    @Inject
    TriageState stateBuilder;

    public Uni<TriageOutcome> triage(Facts facts) {
        QuestionSet qs = questions.get("triage");
        Object state = stateBuilder.build(facts);
        return jev.evaluate(state, qs.questions())
                .map(response -> interpret(facts, response, qs));
    }

    /** Pure interpretation of a triage response — unit-testable with a synthetic {@link JevResponse}. */
    TriageOutcome interpret(Facts facts, JevResponse response, QuestionSet qs) {
        TuningConfig tuning = rulesets.get();
        Thresholds thresholds = tuning.thresholds();

        Map<String, BandedAnswer> answers = gate.band(response, thresholds);
        FactView factView = FactView.of(facts);
        int basePriority = basePriority(answers);

        Decisions decisions = RuleEngine.evaluate(
                answers, factView, tuning.ruleset(), thresholds, basePriority, messages);

        Set<String> escalations = withLowConfidenceEscalation(decisions, answers);
        int blastRadius = effectiveBlastRadius(answers, thresholds);

        Decisions gated = new Decisions(
                decisions.priority(), decisions.findings(), escalations, decisions.silenced());

        return new TriageOutcome(answers, gated, blastRadius, qs.version(), tuning, response);
    }

    /** §5.4(a): effective review-priority starts at intrinsic_risk; invariants/rules only ever raise it. */
    private static int basePriority(Map<String, BandedAnswer> answers) {
        BandedAnswer intrinsicRisk = answers.get("intrinsic_risk");
        return intrinsicRisk == null ? 0 : Math.max(0, intrinsicRisk.scoreInt());
    }

    /**
     * I-5 "low triage confidence raises, never prunes": a concern that triage could not confidently
     * settle (neither silenced nor emitted) routes to its deep confirmation pipeline rather than to
     * silence. This covers both the sub-CONFIRM answers and the confident-but-ambiguous middle (e.g.
     * happy-path-only), on top of the fact-based escalate rules already in the ruleset.
     */
    private static Set<String> withLowConfidenceEscalation(Decisions decisions, Map<String, BandedAnswer> answers) {
        Set<String> escalations = new LinkedHashSet<>(decisions.escalations());
        Set<String> emitted = emittedConcerns(decisions);

        if (!decisions.silenced().contains(TEST_CONCERN) && !emitted.contains(TEST_CONCERN)) {
            escalations.add(PIPELINE_A);
        }
        if (!decisions.silenced().contains(BREAKING_CONCERN) && !emitted.contains(BREAKING_CONCERN)) {
            escalations.add(PIPELINE_C);
        }
        return escalations;
    }

    private static Set<String> emittedConcerns(Decisions decisions) {
        Set<String> concerns = new LinkedHashSet<>();
        for (Finding f : decisions.findings()) {
            concerns.add(f.concern());
        }
        return concerns;
    }

    /**
     * §5.4(d) tail: a sub-CONFIRM change_kind or blast_radius gates blast-radius to at least
     * {@code blastRadiusFloor} (I-5).
     */
    private static int effectiveBlastRadius(Map<String, BandedAnswer> answers, Thresholds thresholds) {
        BandedAnswer blastRadius = answers.get("blast_radius");
        BandedAnswer changeKind = answers.get("change_kind");
        int reported = blastRadius == null ? 0 : Math.max(0, blastRadius.scoreInt());
        boolean radiusUncertain = blastRadius == null || !blastRadius.isConfirm();
        boolean kindUncertain = changeKind == null || !changeKind.isConfirm();
        if (kindUncertain || radiusUncertain) {
            return Math.max(reported, thresholds.named("blastRadiusFloor", 2));
        }
        return reported;
    }
}
