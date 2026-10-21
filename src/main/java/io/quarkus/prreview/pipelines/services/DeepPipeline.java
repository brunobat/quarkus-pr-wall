package io.quarkus.prreview.pipelines.services;


import io.quarkus.prreview.pipelines.model.DeepResult;
import io.quarkus.prreview.pipelines.model.TriageOutcome;
import io.quarkus.prreview.facts.model.Facts;
import io.smallrye.mutiny.Uni;

/**
 * One reserved deep-review pipeline (plan §7). Each is fully independent — a single {@code jev} call over
 * its own state, reading no other pipeline's output — so the orchestrator can fan them out in parallel
 * (plan §10). Deterministic gates (committer status, exempt class, security floor) are applied in
 * {@link #run}'s interpretation, never sent to {@code jev} (invariants I-2, I-3).
 */
public interface DeepPipeline {

    /**
     * The concern key this pipeline owns ({@code "test-adequacy"}, {@code "exempt-class"},
     * {@code "breaking-change"}, {@code "test-weakening"}, {@code "docs-missing"},
     * {@code "domain-routing"}, {@code "inconsistency"}). It is also the pipeline's identity: the key the
     * triage escalation set names, the key the orchestrator indexes by, and the key report provenance shows.
     */
    String concern();

    /** Run the pipeline. Never throws — a failure resolves to an {@link DeepStatus#ERRORED} result. */
    Uni<DeepResult> run(Facts facts, TriageOutcome triage);
}
