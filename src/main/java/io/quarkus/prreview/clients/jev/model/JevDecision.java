package io.quarkus.prreview.clients.jev.model;

import dev.langchain4j.model.decision.response.DecisionResponse;

/** {@code jev}'s response, unchanged, with the size of the submission that produced it and the configured limit. */
public record JevDecision(DecisionResponse response, int submissionChars, int limit) {
}
