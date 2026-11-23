package io.quarkus.prreview.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.quarkus.prreview.classification.model.JevResult;
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
        PrOutcome outcome = classifier.classify(404);
        JevResult.NotAnswered jev = (JevResult.NotAnswered) outcome.jev();

        assertEquals(404, outcome.number());
        assertEquals("gh: GraphQL: Could not resolve to a PullRequest with the number of 404. (repository.pullRequest)", jev.reason());
        assertNull(outcome.pullRequest());
        assertNull(jev.jevModel());
        assertNull(jev.submissionChars());
        verifyNoInteractions(model, summarizer);
    }

    @Test
    void aDiffTooLargeForGitHubIsAGhFailure() {
        PrOutcome outcome = classifier.classify(406);
        JevResult.NotAnswered jev = (JevResult.NotAnswered) outcome.jev();

        assertTrue(jev.reason().startsWith("gh: "), jev.reason());
        assertTrue(jev.reason().contains("too_large"), jev.reason());
        assertEquals(-1, jev.reason().indexOf('\n'), "one line");
        // Only the diff failed: the PR's title was fetched and is kept.
        assertEquals("Reformat everything", outcome.pullRequest().title());
        verifyNoInteractions(model, summarizer);
    }

    @Test
    void anEmptyDiffIsNotSentToJev() {
        PrOutcome outcome = classifier.classify(103);
        JevResult.NotAnswered jev = (JevResult.NotAnswered) outcome.jev();

        assertEquals("empty diff", jev.reason());
        assertEquals("Touch nothing", outcome.pullRequest().title());
        verifyNoInteractions(model, summarizer);
    }

    @Test
    void aRejectedSubmissionIsAJevFailureWithJevsBody() {
        when(model.decide(any(DecisionRequest.class))).thenThrow(new InvalidRequestException(
                "{\"error\": {\"type\": \"max_tokens_exceeded\",\n \"message\": \"too long\"}}"));

        PrOutcome outcome = classifier.classify(101);
        JevResult.NotAnswered jev = (JevResult.NotAnswered) outcome.jev();

        assertEquals("jev: {\"error\": {\"type\": \"max_tokens_exceeded\", \"message\": \"too long\"}}",
                jev.reason());
        assertTrue(jev.message().contains("\n"), "the full message is kept");
        assertEquals("Fix the frobnicator", outcome.pullRequest().title());
        assertEquals("jev-latest", jev.jevModel());
        assertTrue(jev.submissionChars() > 0);
        assertEquals(60000, jev.limit());
        verifyNoInteractions(summarizer);
    }

    @Test
    void anInvalidResponseIsAJevFailure() {
        when(model.decide(any(DecisionRequest.class)))
                .thenThrow(new InvalidDecisionResponseException("probability 1.5 is out of range"));

        assertEquals("jev: probability 1.5 is out of range",
                ((JevResult.NotAnswered) classifier.classify(101).jev()).reason());
        verifyNoInteractions(summarizer);
    }

    @Test
    void aTimeoutIsAJevFailure() {
        when(model.decide(any(DecisionRequest.class)))
                .thenThrow(new RuntimeException("request timed out", new HttpTimeoutException("request timed out")));

        assertEquals("jev: request timed out", ((JevResult.NotAnswered) classifier.classify(101).jev()).reason());
        verifyNoInteractions(summarizer);
    }

    @Test
    void aResponseMissingAnAnswerIsAJevFailure() {
        when(model.decide(any(DecisionRequest.class))).thenReturn(DecisionResponse.builder().modelName("jev-test")
                .answer("test-adequacy.behaviour", YesNoAnswer.of(0.5)).build());

        PrOutcome outcome = classifier.classify(101);
        JevResult.NotAnswered jev = (JevResult.NotAnswered) outcome.jev();

        assertTrue(jev.reason().startsWith("jev: "), jev.reason());
        assertEquals("jev-test", jev.jevModel());
        verify(summarizer, never()).summarize(any());
    }

    @Test
    void everyOutcomeHasAResultFromEverySource() {
        for (int pr : new int[] { 404, 406, 103 }) {
            PrOutcome outcome = classifier.classify(pr);
            assertNotNull(outcome.jev(), "jev for #" + pr);
            assertNotNull(outcome.size(), "size for #" + pr);
            assertNotNull(outcome.ci(), "ci for #" + pr);
            assertNotNull(outcome.provenance(), "provenance for #" + pr);
        }
    }
}
