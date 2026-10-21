package io.quarkus.prreview.report.services;

import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.review.model.ReviewResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shape tests for {@link ProseEnricher} (plan §9, §13): Opus fills a fix line per finding but never changes a
 * severity, and when Opus is not configured the deterministic summary is used verbatim as the fallback.
 */
class ProseEnricherTest {

    private static ProseEnricher enricherWithoutOpus() {
        OpusProseClient opus = new OpusProseClient();
        opus.apiKey = Optional.empty(); // not configured → generate() returns the fallback
        ProseEnricher e = new ProseEnricher();
        e.opus = opus;
        return e;
    }

    private static ReviewResult reviewed(List<Finding> findings) {
        return new ReviewResult(1, "t", 1, 1, findings, List.of(), null, null, ReviewResult.Status.REVIEWED, null);
    }

    @Test
    void fallsBackToSummaryAndPreservesSeverity() {
        Finding f = Finding.of(Severity.BLOCKER, "test-adequacy", "test-missing", "no accompanying test");
        ReviewResult out = enricherWithoutOpus().enrich(reviewed(List.of(f)));
        Finding enriched = out.findings().get(0);
        assertEquals(Severity.BLOCKER, enriched.severity(), "prose never changes severity");
        assertEquals("no accompanying test", enriched.fixProse(), "fallback is the deterministic summary");
    }

    @Test
    void erroredResultPassesThrough() {
        ReviewResult err = ReviewResult.errored(5, "boom");
        assertEquals(err, enricherWithoutOpus().enrich(err));
    }
}
