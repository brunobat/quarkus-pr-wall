package io.quarkus.prreview.facts.model;

/**
 * Deterministically-detectable test-exempt signal for a runtime file (design §4.0, computed by
 * {@link ClassifyChanges} from the diff hunks only — never from PR title/label/body).
 *
 * <p>The judgment-based exempt classes (revert, message-string-only, deprecation-only,
 * covered-by-existing-test) are <em>not</em> here — those are decided by the reasoning layer
 * (deep pipeline B) and must cite a rationale.
 */
public enum ExemptClass {
    COMMENT_ONLY,
    DEPENDENCY_VERSION_ONLY,
    GENERATED_FILE,
    DELETION_ONLY,
    /** Any non-exempt runtime hunk present — mixed changes fail toward requiring a test. */
    NOT_EXEMPT
}
