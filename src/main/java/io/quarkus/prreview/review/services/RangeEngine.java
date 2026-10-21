package io.quarkus.prreview.review.services;


import io.quarkus.prreview.facts.services.FetchPr;
import io.quarkus.prreview.review.model.RangeReport;
import io.quarkus.prreview.review.model.ReviewResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The range/sweep orchestrator (plan §10): loops the single-PR {@link ReviewEngine} in range depth (the gate
 * prunes deep pipelines per PR) and assembles one ranked table. The ranking composite is computed in code —
 * blast radius, then effective priority, then Blocker count — with <em>no</em> {@code jev} call for ordering.
 *
 * <p>Every PR appears in the output, including a dedicated errored section (design §7/§9). PRs are reviewed
 * sequentially so a large sweep does not fan out an unbounded number of concurrent {@code gh}/{@code jev}
 * calls; each PR's own deep pipelines still run in parallel inside the engine.
 */
@ApplicationScoped
public class RangeEngine {

    /** Highest-attention first: wider blast radius, then higher priority, then more Blockers. */
    private static final Comparator<ReviewResult> RANKING = Comparator
            .comparingInt(ReviewResult::blastRadius)
            .thenComparingInt(ReviewResult::priority)
            .thenComparingLong(ReviewResult::blockerCount)
            .reversed();

    @Inject
    ReviewEngine reviewEngine;

    @Inject
    FetchPr fetchPr;

    public Uni<RangeReport> reviewRange(List<Integer> prNumbers) {
        // One GraphQL document for the whole sweep instead of one gh call per PR; best-effort, and
        // every fetch below still works unchanged when it fails.
        Uni<List<ReviewResult>> chain = Uni.createFrom().item(() -> {
            fetchPr.prefetch(prNumbers);
            return new ArrayList<>();
        });
        for (int pr : prNumbers) {
            chain = chain.flatMap(acc -> reviewEngine.review(pr, false).map(result -> {
                acc.add(result);
                return acc;
            }));
        }
        return chain.map(RangeEngine::assemble);
    }

    static RangeReport assemble(List<ReviewResult> results) {
        List<ReviewResult> ranked = new ArrayList<>();
        List<ReviewResult> errored = new ArrayList<>();
        for (ReviewResult r : results) {
            (r.isErrored() ? errored : ranked).add(r);
        }
        ranked.sort(RANKING);
        return new RangeReport(ranked, errored);
    }
}
