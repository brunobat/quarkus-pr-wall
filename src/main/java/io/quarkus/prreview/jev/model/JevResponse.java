package io.quarkus.prreview.jev.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** A {@code jev} response (plan §2). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JevResponse(String model, Map<String, Answer> answers, Usage usage) {

    public JevResponse {
        answers = answers == null ? Map.of() : Map.copyOf(answers);
    }

    public Answer answer(String id) {
        return answers.get(id);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(long input_tokens, long output_tokens) {
    }
}
