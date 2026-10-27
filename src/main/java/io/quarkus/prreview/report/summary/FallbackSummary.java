package io.quarkus.prreview.report.summary;

import java.util.ArrayList;
import java.util.List;

import io.quarkus.prreview.classification.api.CheckResult;
import io.quarkus.prreview.classification.api.QuestionResult;
import io.quarkus.prreview.report.AnswerFormat;
import io.quarkus.prreview.report.summary.model.Summary;
import io.quarkus.prreview.review.model.Classification;

/**
 * The fixed summary used when Claude gives none: every question's answer in contribution order, e.g.
 * {@code jev classification: behaviour 0.88; tests no-test-changes 0.64.}
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
