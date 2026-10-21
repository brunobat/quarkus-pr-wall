package io.quarkus.prreview.interpret.rules.model;

import io.quarkus.prreview.interpret.model.Finding;

import java.util.List;
import java.util.Set;

/**
 * The pure output of {@link RuleEngine#evaluate} (plan §5.6). Everything here is a function of
 * {@code (answers, facts, ruleset, thresholds, basePriority)} with no I/O, so the engine is unit-tested
 * with hand-authored fixtures.
 *
 * @param priority    effective review-priority 0–2 (raise-only; never lowered — invariants I-1/I-2)
 * @param findings    emitted findings, most-severe-per-concern (never includes silenced concerns)
 * @param escalations deep pipelines reserved to run (keys {@code A}…{@code G})
 * @param silenced    concerns cleared to silence by a CONFIRM-clean guarded rule
 */
public record Decisions(
        int priority,
        List<Finding> findings,
        Set<String> escalations,
        Set<String> silenced) {

    public Decisions {
        findings = findings == null ? List.of() : List.copyOf(findings);
        escalations = escalations == null ? Set.of() : Set.copyOf(escalations);
        silenced = silenced == null ? Set.of() : Set.copyOf(silenced);
    }

    public boolean hasBlocker() {
        return findings.stream().anyMatch(f -> f.severity() == io.quarkus.prreview.interpret.model.Severity.BLOCKER);
    }
}
