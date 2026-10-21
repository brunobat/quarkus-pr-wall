package io.quarkus.prreview.report.services;


import io.quarkus.prreview.report.client.OpusMessageClient;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Fault-tolerant façade over the Anthropic Messages API (plan §9). Because Opus is strictly downstream
 * of every gate, an injected instruction in PR text can at worst corrupt advisory prose a human reads,
 * never a gate or a severity. If the API key is absent or the call fails, {@link #generate} falls back
 * to the caller-supplied deterministic template — report rendering never depends on Opus being up.
 */
@ApplicationScoped
public class OpusProseClient {

    @Inject
    @RestClient
    OpusMessageClient client;

    @ConfigProperty(name = "anthropic.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "anthropic.model", defaultValue = "claude-opus-5")
    String model;

    @ConfigProperty(name = "anthropic.version", defaultValue = "2023-06-01")
    String version;

    @ConfigProperty(name = "anthropic.max-tokens", defaultValue = "512")
    int maxTokens;

    public boolean isConfigured() {
        return apiKey.isPresent() && !apiKey.get().isBlank();
    }

    public String modelVersion() {
        return model;
    }

    /**
     * Render one short piece of prose from a system instruction + user prompt, falling back to
     * {@code deterministicFallback} if Opus is unavailable. Blocking by design — callers are already on
     * a worker thread inside the report stage.
     */
    public String generate(String system, String userPrompt, String deterministicFallback) {
        return generate(system, userPrompt, deterministicFallback, maxTokens);
    }

    /**
     * As {@link #generate(String, String, String)} but with a caller-chosen token budget — state
     * compaction (plan §9.4) needs far more room than a one-line fix suggestion.
     */
    public String generate(String system, String userPrompt, String deterministicFallback, int tokens) {
        if (!isConfigured()) {
            return deterministicFallback;
        }
        try {
            return callOpus(system, userPrompt, tokens)
                    .map(r -> {
                        String t = r.text();
                        return (t == null || t.isBlank()) ? deterministicFallback : t.strip();
                    })
                    .onFailure().recoverWithItem(deterministicFallback)
                    .await().indefinitely();
        } catch (RuntimeException e) {
            return deterministicFallback;
        }
    }

    /**
     * Minimal liveness probe for the {@code health} command (plan §12 phase 1): one cheap round-trip
     * that distinguishes "key present but the API is unreachable" from "configured and answering".
     * Never retried — a health check reports the current state rather than papering over it.
     */
    public boolean reachable() {
        if (!isConfigured()) {
            return false;
        }
        var req = new OpusMessageClient.MessageRequest(
                model, 1, "Reply with the single word OK.",
                List.of(new OpusMessageClient.Message("user", "ping")));
        client.create(apiKey.orElseThrow(), version, req).await().indefinitely();
        return true;
    }

    @Retry(maxRetries = 3, delay = 500, delayUnit = ChronoUnit.MILLIS)
    @ExponentialBackoff(factor = 2, maxDelay = 8000)
    @Fallback(fallbackMethod = "callFailed")
    Uni<OpusMessageClient.MessageResponse> callOpus(String system, String userPrompt, int tokens) {
        var req = new OpusMessageClient.MessageRequest(
                model, tokens, system,
                List.of(new OpusMessageClient.Message("user", userPrompt)));
        return client.create(apiKey.orElseThrow(), version, req);
    }

    @SuppressWarnings("unused")
    Uni<OpusMessageClient.MessageResponse> callFailed(String system, String userPrompt, int tokens) {
        return Uni.createFrom().item(new OpusMessageClient.MessageResponse(List.of()));
    }
}
