package io.quarkus.prreview.health.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.prreview.facts.services.GhClient;
import io.quarkus.prreview.health.model.DependencyHealth;
import io.quarkus.prreview.health.model.HealthReport;
import io.quarkus.prreview.jev.services.JevService;
import io.quarkus.prreview.report.services.OpusProseClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Probes every external dependency the agent relies on (plan §12 phase 1): read-only {@code gh}, the
 * {@code jev} decision engine, and Opus. Each probe is the cheapest call that proves the credential and
 * the endpoint actually work, so a misconfiguration surfaces here rather than halfway through a sweep.
 *
 * <p>{@code gh} and {@code jev} are <strong>required</strong> — without them there is no facts layer and
 * no decision engine. Opus is <strong>optional</strong>: the report layer falls back to deterministic
 * summaries, so its absence is a degradation, never a failure.
 */
@ApplicationScoped
public class HealthService {

    /** The cheapest well-formed jev call: one noul over a two-word state. */
    private static final String PROBE_STATE = "A cat sat on a mat.";

    @Inject
    GhClient gh;

    @Inject
    JevService jev;

    @Inject
    OpusProseClient opus;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "gh.repo")
    Optional<String> repo;

    @ConfigProperty(name = "health.timeout-seconds", defaultValue = "20")
    int timeoutSeconds;

    public HealthReport check() {
        return new HealthReport(List.of(checkGh(), checkJev(), checkAnthropic()));
    }

    /** Read-only {@code gh}: the binary runs, is authenticated, and a repo is configured. */
    DependencyHealth checkGh() {
        if (repo.isEmpty() || repo.get().isBlank()) {
            return DependencyHealth.notConfigured("gh", true, "GH_REPO is not set (expected owner/repo)");
        }
        try {
            // 'api' is on the read-only allow-list; /rate_limit proves both the binary and the token.
            gh.run("api", "rate_limit");
            return DependencyHealth.ok("gh", true, "authenticated, repo " + repo.get());
        } catch (RuntimeException | AssertionError e) {
            return DependencyHealth.unreachable("gh", true, message(e));
        }
    }

    /** {@code jev}: the key is present and the endpoint answers a minimal question. */
    DependencyHealth checkJev() {
        if (!jev.isConfigured()) {
            return DependencyHealth.notConfigured("jev", true, "TYPESAFE_API_KEY is not set");
        }
        try {
            var response = jev.evaluate(PROBE_STATE, probeQuestion())
                    .await().atMost(Duration.ofSeconds(timeoutSeconds));
            String model = response == null ? null : response.model();
            return DependencyHealth.ok("jev", true,
                    "model " + (model == null ? jev.modelVersion() : model));
        } catch (RuntimeException e) {
            return DependencyHealth.unreachable("jev", true, message(e));
        }
    }

    /** Opus: optional — absence degrades prose to deterministic summaries, it never blocks a review. */
    DependencyHealth checkAnthropic() {
        if (!opus.isConfigured()) {
            return DependencyHealth.notConfigured("anthropic", false,
                    "ANTHROPIC_API_KEY is not set — fix prose falls back to deterministic summaries");
        }
        try {
            opus.reachable();
            return DependencyHealth.ok("anthropic", false, "model " + opus.modelVersion());
        } catch (RuntimeException e) {
            return DependencyHealth.unreachable("anthropic", false, message(e));
        }
    }

    private ObjectNode probeQuestion() {
        ObjectNode questions = mapper.createObjectNode();
        ObjectNode q = questions.putObject("health_probe");
        q.put("type", "noul");
        q.put("instructions", "the text mentions a cat");
        return questions;
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m.strip();
    }
}
