package io.quarkus.prreview.review.model;

import io.quarkus.prreview.pipelines.model.DeepStatus;

/**
 * Provenance of one deep pipeline's run within a review (design §7/§9): skip means skip <em>analysis</em>,
 * never <em>reporting</em>, so an errored or skipped pipeline still shows up here with its status and note.
 *
 * @param id      pipeline id {@code "A"}…{@code "G"}
 * @param concern the concern the pipeline owns
 * @param status  OK / SKIPPED / ERRORED
 * @param note    short reason (skip reason, error summary); may be null
 */
public record PipelineRun(String id, String concern, DeepStatus status, String note) {
}
