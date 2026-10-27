package io.quarkus.prreview.pipelines.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.prreview.interpret.model.Band;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.questions.model.QuestionSet;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TriagePipeline#inQuestionOrder} — the report's decision table lists one row (or column) per
 * question in the order they appear here, and {@code jev} does not promise to answer them in the order it
 * was asked. Nothing <em>decides</em> on the order; what breaks without this is diffing two reports of the
 * same PR, which was the point of rendering the answers at all.
 */
class TriageAnswerOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static QuestionSet asking(String... ids) throws JsonProcessingException {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < ids.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(ids[i]).append("\":{\"type\":\"noul\"}");
        }
        return new QuestionSet("triage", "v1", MAPPER.readTree(json.append('}').toString()), null, null);
    }

    private static Map<String, BandedAnswer> answered(String... ids) {
        Map<String, BandedAnswer> answers = new LinkedHashMap<>();
        for (String id : ids) {
            answers.put(id, new BandedAnswer(id, Band.CONFIRM, true, null, Double.NaN, null));
        }
        return answers;
    }

    @Test
    void answersComeBackInTheOrderTheQuestionsWereDeclared() throws JsonProcessingException {
        Map<String, BandedAnswer> ordered = TriagePipeline.inQuestionOrder(
                answered("blast_radius", "intrinsic_risk", "change_kind"),
                asking("intrinsic_risk", "change_kind", "blast_radius"));

        assertEquals(List.of("intrinsic_risk", "change_kind", "blast_radius"), List.copyOf(ordered.keySet()));
    }

    @Test
    void anUnansweredQuestionLeavesNoEntryRatherThanANullOne() throws JsonProcessingException {
        Map<String, BandedAnswer> ordered = TriagePipeline.inQuestionOrder(
                answered("intrinsic_risk"),
                asking("intrinsic_risk", "change_kind"));

        assertEquals(List.of("intrinsic_risk"), List.copyOf(ordered.keySet()));
    }

    /**
     * An answer the question set does not know about is the one thing worth seeing in the report, so it is
     * appended rather than dropped — silently discarding it would hide the mismatch everywhere.
     */
    @Test
    void anAnswerToAnUnaskedQuestionIsKeptAfterTheDeclaredOnes() throws JsonProcessingException {
        Map<String, BandedAnswer> ordered = TriagePipeline.inQuestionOrder(
                answered("surprise", "intrinsic_risk"),
                asking("intrinsic_risk"));

        assertEquals(List.of("intrinsic_risk", "surprise"), List.copyOf(ordered.keySet()));
    }
}
