package io.quarkus.prreview.classification.api;

import io.quarkus.prreview.pr.model.PrContext;

/**
 * One classification check. All checks contribute PR-level questions to a single {@code jev} submission per PR,
 * answered in a single call; no check calls {@code jev} itself. A check only asks: its answers come back to it
 * unchanged, grouped under its id, with no thresholds or interpretation.
 */
public interface ClassificationCheck {

    /** Namespaces this check's question ids, e.g. {@code test-adequacy}. */
    String id();

    /** Contributes this check's questions about the PR. */
    void ask(PrContext pr, CheckQuestions questions);
}
