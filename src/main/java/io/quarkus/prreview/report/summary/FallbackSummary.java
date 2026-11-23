package io.quarkus.prreview.report.summary;

import java.util.ArrayList;
import java.util.List;

import io.quarkus.prreview.classification.jev.model.CheckResult;
import io.quarkus.prreview.classification.jev.model.Classification;
import io.quarkus.prreview.classification.jev.model.QuestionResult;
import io.quarkus.prreview.report.AnswerFormat;

/**
 * The fixed summary used when Claude gives none: every question's answer in contribution order, e.g.
 * {@code jev classification: behaviour 0.88; tests-changed 0.08; reach none 0.83; assert nothing-executed 0.88.}
 */
public final class FallbackSummary {

    private FallbackSummary() {
    }

    public static Summary of(Classification classification) {
        List<String> parts = new ArrayList<>();
        for (CheckResult check : classification.checks()) {
            for (QuestionResult question : check.questions()) {
                parts.add(question.label() + " " + AnswerFormat.shortAnswer(question.answer()));
            }
        }
        return new Summary("jev classification: " + String.join("; ", parts) + ".", null);
    }
}
