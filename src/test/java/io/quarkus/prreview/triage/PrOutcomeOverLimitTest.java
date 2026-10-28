package io.quarkus.prreview.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.decision.DecisionModel;
import io.quarkus.prreview.clients.claude.ClassificationSummarizer;
import io.quarkus.prreview.it.support.FakeGh;
import io.quarkus.prreview.triage.model.PrOutcome;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@WithTestResource(FakeGh.class)
@TestProfile(PrOutcomeOverLimitTest.LowLimit.class)
class PrOutcomeOverLimitTest {

    public static class LowLimit implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("pr-review.jev.max-submission-chars", "1000");
        }
    }

    @InjectMock
    DecisionModel model;

    @InjectMock
    ClassificationSummarizer summarizer;

    @Inject
    PrClassifier classifier;

    @Test
    void aSubmissionOverTheLimitIsNotSent() {
        PrOutcome.NotClassified outcome = (PrOutcome.NotClassified) classifier.classify(101);

        assertTrue(outcome.submissionChars() > 1000, outcome.toString());
        assertEquals("submission " + outcome.submissionChars() + " chars exceeds limit 1000", outcome.reason());
        assertEquals(1000, outcome.limit());
        assertEquals("jev-latest", outcome.jevModel());
        assertEquals("Fix the frobnicator", outcome.pullRequest().title());
        verifyNoInteractions(model, summarizer);
    }
}
