package io.quarkus.prreview.classification.api;

import dev.langchain4j.model.decision.request.ChoiceQuestion;
import dev.langchain4j.model.decision.request.YesNoQuestion;

/** Where one check adds its questions; ids are scoped to that check. */
public interface CheckQuestions {

    /**
     * Adds a yes/no question.
     *
     * @param questionId unique within the check, e.g. {@code behaviour}
     * @param label short name shown in the output, e.g. {@code behaviour}
     */
    void yesNo(String questionId, String label, YesNoQuestion question);

    /**
     * Adds a choice question; {@code jev} picks one of its options and gives a probability for each.
     *
     * @param questionId unique within the check, e.g. {@code tests}
     * @param label short name shown in the output, e.g. {@code tests}
     */
    void choice(String questionId, String label, ChoiceQuestion question);
}
