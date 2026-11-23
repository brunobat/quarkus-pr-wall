package io.quarkus.prreview.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.decision.DecisionModel;
import io.quarkus.prreview.classification.github.size.model.PrSize;
import io.quarkus.prreview.classification.model.JevResult;
import io.quarkus.prreview.classification.source.model.SourceResult;
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
            return Map.of("pr-review.jev.max-submission-tokens", "1000");
        }
    }

    @InjectMock
    DecisionModel model;

    @InjectMock
    ClassificationSummarizer summarizer;

    @Inject
    PrClassifierService classifier;

    @Test
    void aSubmissionOverTheLimitIsNotSent() {
        PrOutcome outcome = classifier.classify(101);
        JevResult.NotAnswered jev = (JevResult.NotAnswered) outcome.jev();

        assertTrue(jev.submissionChars() > 0, outcome.toString());
        assertEquals((int) Math.ceil(jev.submissionChars() / 3.0), jev.estimatedTokens());
        assertEquals("submission estimated at " + jev.estimatedTokens() + " tokens + 1000 for the answer exceeds limit 1000",
                jev.reason());
        assertEquals(1000, jev.limit());
        assertEquals("jev-latest", jev.jevModel());
        assertEquals("Fix the frobnicator", outcome.pullRequest().title());
        assertEquals(new SourceResult.Available<>(new PrSize(2, 12, 3)), outcome.size());
        verifyNoInteractions(model, summarizer);
    }
}
