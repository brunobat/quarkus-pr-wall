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

    /** Stable id {@code "A"}…{@code "G"} used by the triage escalation set and report provenance. */
    String id();

    /** The concern key this pipeline owns (e.g. {@code "test-adequacy"}). */
    String concern();

    /** Run the pipeline. Never throws — a failure resolves to an {@link DeepStatus#ERRORED} result. */
    Uni<DeepResult> run(Facts facts, TriageOutcome triage);
}
