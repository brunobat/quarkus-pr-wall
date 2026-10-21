package io.quarkus.prreview.review.model;

import java.util.List;

/**
 * The result of a range review (plan §10): every PR appears, split into the ranked reviewed rows and a
 * dedicated errored section (design §7/§9) — skip means skip <em>analysis</em>, never <em>reporting</em>.
 *
 * @param ranked  reviewed PRs, highest-attention first (blast radius, then priority, then Blocker count)
 * @param errored PRs that could not be reviewed automatically, preserved with their error note
 */
public record RangeReport(List<ReviewResult> ranked, List<ReviewResult> errored) {

    public RangeReport {
        ranked = ranked == null ? List.of() : List.copyOf(ranked);
        errored = errored == null ? List.of() : List.copyOf(errored);
    }
}
