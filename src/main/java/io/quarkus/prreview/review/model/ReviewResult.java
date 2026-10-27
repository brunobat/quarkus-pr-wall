package io.quarkus.prreview.review.model;

import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete structured result of reviewing one PR (plan §10, §13): the final combined findings plus the
 * ranking inputs and full provenance. This is the IR the report layer renders and that evals assert against —
 * no model prose is involved in any field here, so wording/model drift never breaks the eval suite.
 *
 * <p>An errored PR (facts could not be gathered, or the review itself failed) is still a first-class result
 * with {@link Status#ERRORED}: skip means skip <em>analysis</em>, never <em>reporting</em> (design §7/§9).
 *
 * @param prNumber   the PR number
 * @param title      the PR title (null when the PR could not be fetched)
 * @param priority   effective review-priority 0–2 (raise-only; §5, I-1/I-2)
 * @param blastRadius effective blast radius 0–4 used for range ranking (§7, §10)
 * @param findings   the final combined findings, most-severe-per-concern (never includes silenced concerns)
 * @param pipelines  per-pipeline run provenance (OK/SKIPPED/ERRORED)
 * @param triageAnswers every triage answer after the confidence gate, for the report's decision table.
 *                      Carries {@link BandedAnswer#raw()}, so the underlying probabilities are visible
 *                      <em>in the report</em> — they are still never read as a second gate decision, which
 *                      is the line {@code BandedAnswer} draws. Empty when the PR errored.
 * @param provenance model + config versions for reproducibility (§11)
 * @param authorContext what the PR attests about who opened it; null when unresolved. Reporting-only —
 *                      nothing downstream reads it, which is how I-7 stays true by construction.
 * @param status     REVIEWED or ERRORED
 * @param note       error summary when ERRORED; null otherwise
 */
public record ReviewResult(
        int prNumber,
        String title,
        int priority,
        int blastRadius,
        List<Finding> findings,
        List<PipelineRun> pipelines,
        Map<String, BandedAnswer> triageAnswers,
        Provenance provenance,
        AuthorContext authorContext,
        Status status,
        String note) {

    public enum Status {
        REVIEWED,
        ERRORED
    }

    public ReviewResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        pipelines = pipelines == null ? List.of() : List.copyOf(pipelines);
        // Not Map.copyOf: the gate returns the answers in question order, and the decision table's
        // columns follow it. An unordered copy would reshuffle the report between runs.
        triageAnswers = triageAnswers == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(triageAnswers));
    }

    public long blockerCount() {
        return findings.stream().filter(f -> f.severity() == Severity.BLOCKER).count();
    }

    public boolean hasBlocker() {
        return blockerCount() > 0;
    }

    public boolean isErrored() {
        return status == Status.ERRORED;
    }

    public static ReviewResult errored(int prNumber, String note) {
        return new ReviewResult(prNumber, null, 0, 0, List.of(), List.of(), Map.of(), null, null,
                Status.ERRORED, note);
    }

    /** Return a copy with the findings replaced (used by the Opus prose enricher, strictly downstream). */
    public ReviewResult withFindings(List<Finding> replacement) {
        return new ReviewResult(prNumber, title, priority, blastRadius, replacement, pipelines, triageAnswers,
                provenance, authorContext, status, note);
    }
}
