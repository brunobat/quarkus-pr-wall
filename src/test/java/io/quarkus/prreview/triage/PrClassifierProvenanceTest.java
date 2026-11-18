package io.quarkus.prreview.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.DecisionRequest;
import io.quarkus.prreview.classification.model.JevResult;
import io.quarkus.prreview.classification.provenance.ProvenanceSource;
import io.quarkus.prreview.classification.source.model.SourceResult;
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
    ProvenanceSource provenance;

    @Inject
    PrClassifier classifier;

    @Test
    void anUnexpectedProvenanceFailureIsUnavailableAndJevsResultIsUnchanged() {
        when(model.decide(any(DecisionRequest.class))).thenThrow(new RuntimeException("jev is down"));
        when(provenance.gather(101)).thenThrow(new IllegalStateException("something\nbroke"));

        PrOutcome outcome = classifier.classify(101);

        assertEquals(SourceResult.unavailable("something broke"), outcome.provenance());
        assertEquals("jev: jev is down", ((JevResult.NotAnswered) outcome.jev()).reason());
    }
}
