package io.quarkus.prreview.clients.jev;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.request.DecisionRequestParameters;
import io.quarkus.prreview.clients.jev.model.JevDecision;
import io.quarkus.prreview.config.ApiKey;
import io.quarkus.prreview.config.ReviewConfig;

/**
 * The only access to {@code jev}. Each submission is measured before sending; one over the configured limit is
 * refused with a {@link SubmissionTooLargeException}, without an HTTP call. Otherwise it is sent once, with no retry, and any failure becomes a
 * {@link JevException}.
 */
@ApplicationScoped
public class JevClient {

    private final DecisionModel model;
    private final ReviewConfig config;
    private final String modelName;
    private final String apiKey;

    public JevClient(DecisionModel model, ReviewConfig config,
            @ConfigProperty(name = "quarkus.langchain4j.typesafe.decision-model.model-name") String modelName,
            @ConfigProperty(name = "quarkus.langchain4j.typesafe.api-key") String apiKey) {
        this.model = model;
        this.config = config;
        this.modelName = modelName;
        this.apiKey = apiKey;
    }

    /** Whether a {@code jev} API key is set; without one every submission would fail the same way. */
    public boolean keyConfigured() {
        return !ApiKey.NOT_SET.equals(apiKey);
    }

    /** The configured {@code jev} model name, sent with every submission. */
    public String modelName() {
        return modelName;
    }

    public JevDecision decide(DecisionRequest request) {
        // The model name is set here, not added later by the model, so the measured body is the one sent.
        DecisionRequest sent = request.toBuilder()
                .parameters(DecisionRequestParameters.builder().modelName(modelName).build()
                        .overrideWith(request.parameters()))
                .build();
        int size = SubmissionSize.of(sent);
        int limit = config.jev().maxSubmissionChars();
        if (size > limit) {
            throw new SubmissionTooLargeException(size, limit);
        }
        try {
            return new JevDecision(model.decide(sent), size, limit);
        } catch (RuntimeException e) {
            throw new JevException(e.getMessage() == null ? e.getClass().getName() : e.getMessage(), size, limit, e);
        }
    }
}
