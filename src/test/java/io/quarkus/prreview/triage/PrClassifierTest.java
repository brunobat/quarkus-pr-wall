package io.quarkus.prreview.triage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.clients.claude.ClassificationSummarizer;
import io.quarkus.prreview.it.support.FakeGh;
import io.quarkus.prreview.triage.model.PrOutcome;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@WithTestResource(FakeGh.class)
class PrClassifierTest {

    @InjectMock
    DecisionModel model;

    @InjectMock
    ClassificationSummarizer summarizer;

    @Inject
    PrClassifier classifier;

    @Test
    void onePrIsOneJevCallAndAnswersPassThroughUnchanged() {
        when(model.decide(any(DecisionRequest.class))).thenReturn(DecisionResponse.builder()
                .modelName("jev-test")
                .tokenUsage(new TokenUsage(10, 2))
                .answer("test-adequacy.behaviour", YesNoAnswer.of(0.4999))
                .answer("test-adequacy.tests-changed", YesNoAnswer.of(0.9))
                .answer("test-adequacy.reach", ChoiceAnswer.builder().value("undetermined")
                        .probability("all", 0.7).probability("undetermined", 0.3).build())
                .answer("test-adequacy.assert", ChoiceAnswer.builder().value("all")
                        .probability("all", 1.0).build())
                .build());

        when(summarizer.summarize(any())).thenReturn(Result.<String>builder().content("A summary.").build());

        PrOutcome.Classified outcome = (PrOutcome.Classified) classifier.classify(101);
        Classification classification = outcome.classification();
        assertEquals("A summary.", outcome.summary().text());

        ArgumentCaptor<DecisionRequest> sent = ArgumentCaptor.forClass(DecisionRequest.class);
        verify(model, times(1)).decide(sent.capture());
        assertEquals(List.of("test-adequacy.behaviour", "test-adequacy.tests-changed", "test-adequacy.reach",
                "test-adequacy.assert"), List.copyOf(sent.getValue().questions().keySet()));
        assertEquals("jev-latest", sent.getValue().modelName());

        List<QuestionResult> answers = classification.checks().getFirst().questions();
        assertEquals(new QuestionResult.YesNo(0.4999), answers.get(0).answer());
        assertEquals(new QuestionResult.YesNo(0.9), answers.get(1).answer());
        QuestionResult.Choice reach = (QuestionResult.Choice) answers.get(2).answer();
        assertEquals("undetermined", reach.value());
        assertEquals(0.7, reach.probabilities().get("all"));
        assertEquals("all", ((QuestionResult.Choice) answers.get(3).answer()).value());
        assertEquals("jev-test", classification.jevModel());
        assertEquals(10, classification.tokenUsage().inputTokenCount());
        assertEquals(101, classification.pr().pullRequest().number());
        assertEquals("core/runtime/src/main/java/io/quarkus/Foo.java",
                classification.pr().changedFiles().getFirst().path());
    }
}
