package io.quarkus.prreview.classification.jev.model;

import java.util.List;

/** One check's answers, in the order it asked its questions. */
public record CheckResult(String checkId, List<QuestionResult> questions) {
}
