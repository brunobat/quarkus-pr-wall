package io.quarkus.prreview.pipelines.services;

import io.quarkus.prreview.facts.model.Facts;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;

import java.util.List;
import java.util.Optional;

/**
 * Small deterministic helpers shared by the deep-pipeline interpretation functions (plan §7).
 */
final class Verdicts {

    private Verdicts() {
    }

    /**
     * The §4 committer asymmetry, from the <em>pinned</em> committer-status fact (never a jev answer —
     * invariants I-2, I-3): a hard finding is a Blocker for non-committers, a Needs-attention for
     * committers (and, conservatively, for the unknown case — we do not over-block on missing identity).
     */
    static Severity byCommitter(Facts facts) {
        return facts.committerStatus().isNonCommitter() ? Severity.BLOCKER : Severity.NEEDS_ATTENTION;
    }

    /** Collapse a pipeline's candidate findings to the single most-severe one (empty ⇒ silence). */
    static List<Finding> mostSevere(List<Finding> candidates) {
        Optional<Finding> worst = candidates.stream()
                .min((a, b) -> Integer.compare(a.severity().ordinal(), b.severity().ordinal()));
        return worst.map(List::of).orElseGet(List::of);
    }
}
