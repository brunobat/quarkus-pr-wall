package io.quarkus.prreview.pipelines.model;

/**
 * The outcome status of a deep pipeline run (plan §10). A pipeline that could not complete is
 * {@link #ERRORED} — never silently dropped: the report still shows the PR with its status (design §7/§9,
 * "skip means skip analysis, never reporting").
 */
public enum DeepStatus {
    /** Ran to completion (may still have produced no findings). */
    OK,
    /** Not run because a deterministic gate settled the concern without a jev call. */
    SKIPPED,
    /** The jev call failed after retries / the pipeline could not produce a verdict. */
    ERRORED
}
