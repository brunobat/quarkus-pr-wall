package io.quarkus.prreview.jev.services;



import io.quarkus.prreview.jev.model.JevRequest;
import io.quarkus.prreview.jev.client.JevClient;
import io.quarkus.prreview.jev.client.JevException;
import io.quarkus.prreview.jev.model.JevResponse;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Fault-tolerant façade over {@link JevClient} (plan §3, §10). Every pipeline evaluates its own state
 * through this one method, so the retry/backoff/circuit-breaker policy lives in exactly one place.
 *
 * <p>Fan-out is nearly free (billed on input tokens only, ~$0.042/1M, output ~$0) and all questions in
 * a call are evaluated in parallel, so a pipeline asks every question it could need in one call and
 * lets code decide what matters (plan §2).
 */
@ApplicationScoped
public class JevService {

    @Inject
    @RestClient
    JevClient client;

    @ConfigProperty(name = "jev.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "jev.model", defaultValue = "jev-latest")
    String model;

    /**
     * Evaluate one pipeline's state against its question block. Retries 429/529 with exponential
     * backoff and aborts immediately on 401/422 ({@link JevException.Fatal}) — a 422 is a bug in our
     * question JSON and must fail loudly (plan §10).
     */
    @Retry(maxRetries = 4, delay = 500, delayUnit = ChronoUnit.MILLIS,
            retryOn = JevException.Retryable.class, abortOn = JevException.Fatal.class)
    @ExponentialBackoff(factor = 2, maxDelay = 8000)
    @CircuitBreaker(requestVolumeThreshold = 8, failureRatio = 0.5, delay = 10_000)
    public Uni<JevResponse> evaluate(Object state, JsonNode questions) {
        if (!isConfigured()) {
            return Uni.createFrom().failure(
                    new JevException.Fatal(401, "TYPESAFE_API_KEY is not configured"));
        }
        return client.evaluate("Bearer " + apiKey.get(), new JevRequest(state, model, questions));
    }

    public String modelVersion() {
        return model;
    }

    public boolean isConfigured() {
        return apiKey.isPresent() && !apiKey.get().isBlank();
    }
}
