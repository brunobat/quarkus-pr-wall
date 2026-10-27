package io.quarkus.prreview.classification.api;

import dev.langchain4j.model.decision.request.YesNoQuestion;

/** Where one check adds its questions; ids are scoped to that check. */
public interface CheckQuestions {

    /**
     * Adds a yes/no question.
     *
     * @param questionId unique within the check, e.g. {@code behaviour}
     * @param label short name shown in the output, e.g. {@code adequately tested}
     */
    void yesNo(String questionId, String label, YesNoQuestion question);
}
