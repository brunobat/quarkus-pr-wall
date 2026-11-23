package io.quarkus.prreview.classification.checks.testadequacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.decision.request.ChoiceQuestion;

/**
 * Pins the shape of the question set: which questions are asked and the ids of each choice's options, which the rest
 * of the app, the {@code jev} stub and the README rely on. The wording is left free, so it can be tried against
 * {@code jev} without changing this test; only that every text is present is checked.
 */
class TestAdequacyCheckTest {

    @Test
    void yesNoQuestionsHaveTheirTexts() {
        for (var question : List.of(TestAdequacyCheck.BEHAVIOUR, TestAdequacyCheck.TESTS_CHANGED)) {
            assertFalse(question.text().isBlank());
            assertFalse(question.yesWhen().isBlank());
            assertFalse(question.noWhen().isBlank());
        }
    }

    @Test
    void reachOptions() {
        assertOptions(TestAdequacyCheck.REACH, List.of("all", "some", "none", "no-runtime-change", "undetermined"));
    }

    @Test
    void assertOptions() {
        assertOptions(TestAdequacyCheck.ASSERTS, List.of("all", "some", "none", "nothing-executed", "undetermined"));
    }

    private static void assertOptions(ChoiceQuestion question, List<String> ids) {
        assertFalse(question.text().isBlank());
        assertEquals(ids, List.copyOf(question.options().keySet()));
        question.options().forEach((id, description) -> assertFalse(description.isBlank(), id));
    }
}
