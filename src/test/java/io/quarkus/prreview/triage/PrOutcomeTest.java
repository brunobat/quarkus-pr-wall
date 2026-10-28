package io.quarkus.prreview.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.http.HttpTimeoutException;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import dev.langchain4j.exception.InvalidDecisionResponseException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import io.quarkus.prreview.clients.claude.ClassificationSummarizer;
import io.quarkus.prreview.it.support.FakeGh;
import io.quarkus.prreview.triage.model.PrOutcome;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;

/** Each per-PR failure becomes a not-classified outcome with its one-line reason; none is thrown. */
@QuarkusTest
@WithTestResource(FakeGh.class)
class PrOutcomeTest {

    @InjectMock
    DecisionModel model;

    @InjectMock
    ClassificationSummarizer summarizer;

    @Inject
    PrClassifier classifier;

    @Test
    void aPrGhCannotFindIsAGhFailure() {
        PrOutcome.NotClassified outcome = (PrOutcome.NotClassified) classifier.classify(404);

        assertEquals(404, outcome.number());
        assertEquals("gh: no pull requests found for branch \"404\"", outcome.reason());
        assertNull(outcome.pullRequest());
        assertNull(outcome.jevModel());
        assertNull(outcome.submissionChars());
        verifyNoInteractions(model, summarizer);
    }

    @Test
    void aDiffTooLargeForGitHubIsAGhFailure() {
        PrOutcome.NotClassified outcome = (PrOutcome.NotClassified) classifier.classify(406);

        assertTrue(outcome.reason().startsWith("gh: "), outcome.reason());
        assertTrue(outcome.reason().contains("too_large"), outcome.reason());
        assertEquals(-1, outcome.reason().indexOf('\n'), "one line");
        // Only the diff failed: the PR's title was fetched and is kept.
        assertEquals("Reformat everything", outcome.pullRequest().title());
        verifyNoInteractions(model, summarizer);
    }

    @Test
    void anEmptyDiffIsNotSentToJev() {
        PrOutcome.NotClassified outcome = (PrOutcome.NotClassified) classifier.classify(103);

        assertEquals("empty diff", outcome.reason());
        assertEquals("Touch nothing", outcome.pullRequest().title());
        verifyNoInteractions(model, summarizer);
    }

    @Test
    void aRejectedSubmissionIsAJevFailureWithJevsBody() {
        when(model.decide(any(DecisionRequest.class))).thenThrow(new InvalidRequestException(
                "{\"error\": {\"type\": \"max_tokens_exceeded\",\n \"message\": \"too long\"}}"));

        PrOutcome.NotClassified outcome = (PrOutcome.NotClassified) classifier.classify(101);

        assertEquals("jev: {\"error\": {\"type\": \"max_tokens_exceeded\", \"message\": \"too long\"}}",
                outcome.reason());
        assertTrue(outcome.message().contains("\n"), "the full message is kept");
        assertEquals("Fix the frobnicator", outcome.pullRequest().title());
        assertEquals("jev-latest", outcome.jevModel());
        assertTrue(outcome.submissionChars() > 0);
        assertEquals(60000, outcome.limit());
        verifyNoInteractions(summarizer);
    }

    @Test
    void anInvalidResponseIsAJevFailure() {
        when(model.decide(any(DecisionRequest.class)))
                .thenThrow(new InvalidDecisionResponseException("probability 1.5 is out of range"));

        assertEquals("jev: probability 1.5 is out of range",
                ((PrOutcome.NotClassified) classifier.classify(101)).reason());
        verifyNoInteractions(summarizer);
    }

    @Test
    void aTimeoutIsAJevFailure() {
        when(model.decide(any(DecisionRequest.class)))
                .thenThrow(new RuntimeException("request timed out", new HttpTimeoutException("request timed out")));

        assertEquals("jev: request timed out", ((PrOutcome.NotClassified) classifier.classify(101)).reason());
        verifyNoInteractions(summarizer);
    }

    @Test
    void aResponseMissingAnAnswerIsAJevFailure() {
        when(model.decide(any(DecisionRequest.class))).thenReturn(DecisionResponse.builder().modelName("jev-test")
                .answer("test-adequacy.behaviour", YesNoAnswer.of(0.5)).build());

        PrOutcome.NotClassified outcome = (PrOutcome.NotClassified) classifier.classify(101);

        assertTrue(outcome.reason().startsWith("jev: "), outcome.reason());
        assertEquals("jev-test", outcome.jevModel());
        verify(summarizer, never()).summarize(any());
    }
}
