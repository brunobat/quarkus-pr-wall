package io.quarkus.prreview.clients.jev;

import dev.langchain4j.internal.Json;
import dev.langchain4j.internal.ProviderJson;
import dev.langchain4j.internal.ProviderJsonSpec;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.typesafe.internal.TypeSafeMapper;

/**
 * The size of a {@code jev} submission: the characters of the JSON body that {@code langchain4j-typesafe} posts,
 * built with that module's own mapper and a codec with the same spec as its {@code TypeSafeClient}.
 * Both are internal to LangChain4j; the end-to-end test compares this size with the body actually received.
 */
final class SubmissionSize {

    /** Same spec as {@code TypeSafeClient.CODEC}. */
    private static final Json.JsonCodec CODEC = ProviderJson.codec(ProviderJsonSpec.builder()
            .propertyNaming(ProviderJsonSpec.PropertyNaming.SNAKE_CASE)
            .build());

    private SubmissionSize() {
    }

    /** The request must already carry the model name, as the model would send it. */
    static int of(DecisionRequest request) {
        return CODEC.toJson(TypeSafeMapper.toTypeSafeRequest(request)).length();
    }
}
