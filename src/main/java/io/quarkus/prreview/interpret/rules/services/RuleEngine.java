package io.quarkus.prreview.interpret.rules.services;


import io.quarkus.prreview.interpret.rules.model.Decisions;
import io.quarkus.prreview.interpret.rules.model.Rule;
import io.quarkus.prreview.interpret.rules.model.Ruleset;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.services.FactView;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.interpret.model.Thresholds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The pure interpretation kernel (plan §5.6). Given banded answers, deterministic facts, the tunable
 * ruleset, the thresholds, and a base priority, it produces {@link Decisions} with <strong>no I/O</strong>
 * — so confluence and the anti-gaming invariants are unit-testable with hand-authored fixtures (plan §13).
 *
 * <p>The engine is <em>confluent</em>: every effect is combined order-independently —
 * <ul>
 *   <li>{@code priority} is a {@code max} over the base, the compiled security floor (I-1), every
 *       {@code raise}/{@code floor} rule, and the raise-only anomaly bump (I-2);</li>
 *   <li>{@code findings} keep the most-severe emit per concern (adding a rule can only raise a verdict —
 *       I-6), ties broken by rule id;</li>
 *   <li>{@code escalations} are a set union (additive only);</li>
 *   <li>{@code silenced} is the weakest effect — a concern is silenced only if a CONFIRM-clean guarded
 *       rule fired (I-5) <em>and</em> nothing emitted or escalated it.</li>
 * </ul>
 * Rule order in the file therefore cannot change any safety-relevant output.
 */
public final class RuleEngine {

    private RuleEngine() {
    }

    public static Decisions evaluate(
            Map<String, BandedAnswer> answers,
            FactView facts,
            Ruleset ruleset,
            Thresholds thresholds,
            int basePriority,
            MessageCatalog messages) {

        int priority = Math.max(basePriority, InvariantRules.securityFloor(facts, thresholds));

        // Most-severe emit per concern; insertion order kept for deterministic reporting.
        Map<String, Finding> emittedByConcern = new LinkedHashMap<>();
        TreeSet<String> escalations = new TreeSet<>();
        TreeSet<String> silenceCandidates = new TreeSet<>();

        for (Rule rule : ruleset.enabled()) {
            if (!rule.matches(answers, facts)) {
                continue;
            }
            switch (rule.then()) {
                case raisePriority, floorPriority -> {
                    if (rule.value() != null) {
                        priority = Math.max(priority, rule.value());
                    }
                }
                case escalate -> {
                    if (rule.pipeline() != null) {
                        escalations.add(rule.pipeline());
                    }
                }
                case emit -> acceptEmit(emittedByConcern, rule, answers, messages);
                case silence -> {
                    if (InvariantRules.silenceAllowed(rule) && rule.concern() != null) {
                        silenceCandidates.add(rule.concern());
                    }
                }
            }
        }

        // I-2: an anomaly may only raise priority, never above the "read closely" ceiling.
        priority = InvariantRules.applyAnomaly(priority, answers, facts);

        // Silence is the weakest effect: an emitted or escalated concern can never be silenced (I-6).
        TreeSet<String> silenced = new TreeSet<>(silenceCandidates);
        silenced.removeAll(emittedByConcern.keySet());
        silenced.removeAll(escalations);

        List<Finding> findings = new ArrayList<>(emittedByConcern.values());
        return new Decisions(priority, findings, escalations, silenced);
    }

    /**
     * Merge an {@code emit} rule into the per-concern map, keeping the most severe finding; a tie is
     * broken deterministically by the smaller rule id so the result is independent of file order.
     */
    private static void acceptEmit(
            Map<String, Finding> byConcern,
            Rule rule,
            Map<String, BandedAnswer> answers,
            MessageCatalog messages) {

        Severity severity = rule.severity() == null ? Severity.FYI : rule.severity();
        if (!severity.isEmitted()) {
            return;
        }
        String concern = rule.concern() == null ? rule.id() : rule.concern();
        boolean tentative = rule.triggeredAtFlag(answers);

        Finding candidate = Finding
                .of(severity, concern, rule.message(), messages.summary(rule.message()))
                .tentative(tentative);
        if (rule.file() != null) {
            candidate = candidate.at(rule.file(), rule.line() == null ? 0 : rule.line());
        }
        candidate = candidate.withRationale("rule:" + rule.id());

        Finding existing = byConcern.get(concern);
        if (existing == null || preferCandidate(candidate, severity, existing, rule.id())) {
            byConcern.put(concern, candidate);
        }
    }

    private static boolean preferCandidate(Finding candidate, Severity candidateSeverity,
            Finding existing, String candidateRuleId) {
        if (candidateSeverity.moreSevereThan(existing.severity())) {
            return true;
        }
        if (existing.severity().moreSevereThan(candidateSeverity)) {
            return false;
        }
        // Equal severity: deterministic tie-break by rule id (rationale holds "rule:<id>").
        String existingRuleId = existing.rationale() == null ? "" : existing.rationale();
        String candidateMarker = "rule:" + candidateRuleId;
        return candidateMarker.compareTo(existingRuleId) < 0;
    }
}
