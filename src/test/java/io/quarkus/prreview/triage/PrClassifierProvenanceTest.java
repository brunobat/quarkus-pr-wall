package io.quarkus.prreview.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.DecisionRequest;
import io.quarkus.prreview.classification.github.SizeService;
import io.quarkus.prreview.classification.model.JevResult;
import io.quarkus.prreview.classification.model.SourceResult;
import io.quarkus.prreview.classification.provenance.ProvenanceService;
import io.quarkus.prreview.it.support.FakeGh;
import io.quarkus.prreview.triage.model.PrOutcome;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;

/** Provenance runs beside {@code jev}; whatever happens to it, {@code jev}'s result is unchanged. */
@QuarkusTest
@WithTestResource(FakeGh.class)
class PrClassifierProvenanceTest {

    @InjectMock
    DecisionModel model;

    @InjectMock
    ProvenanceService provenance;

    @InjectMock
    SizeService size;

    @Inject
    PrClassifierService classifier;

    @Test
    void anUnexpectedProvenanceFailureIsUnavailableAndJevsResultIsUnchanged() {
        when(model.decide(any(DecisionRequest.class))).thenThrow(new RuntimeException("jev is down"));
        when(provenance.gather(101)).thenThrow(new IllegalStateException("something\nbroke"));

        PrOutcome outcome = classifier.classify(101);

        assertEquals(SourceResult.unavailable(), outcome.provenance());
        assertEquals("jev: jev is down", ((JevResult.NotAnswered) outcome.jev()).reason());
    }

    @Test
    void theLookupsAreStoppedWhenTheRestOfThePrFails() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        // jev answers only once the lookups are under way, so the failure below always finds them running.
        when(model.decide(any(DecisionRequest.class))).thenAnswer(call -> {
            started.await(5, TimeUnit.SECONDS);
            throw new RuntimeException("jev is down");
        });
        when(size.gather(101)).thenThrow(new IllegalStateException("size broke"));
        when(provenance.gather(101)).thenAnswer(call -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                stopped.countDown();
            }
            return SourceResult.unavailable();
        });

        assertThrows(IllegalStateException.class, () -> classifier.classify(101));

        assertTrue(started.await(5, TimeUnit.SECONDS), "the lookups never started");
        assertTrue(stopped.await(5, TimeUnit.SECONDS), "the lookups were left running");
    }
}
