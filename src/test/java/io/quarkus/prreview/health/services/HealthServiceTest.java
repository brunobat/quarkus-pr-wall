package io.quarkus.prreview.health.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.prreview.facts.services.GhClient;
import io.quarkus.prreview.health.model.DependencyHealth;
import io.quarkus.prreview.health.model.HealthReport;
import io.quarkus.prreview.jev.model.JevResponse;
import io.quarkus.prreview.jev.services.JevService;
import io.quarkus.prreview.report.services.OpusProseClient;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HealthService} (plan §12 phase 1): the probe reports configuration state without
 * reaching the network, and — the part that actually matters — a missing Opus key degrades the report
 * rather than failing it, because the report layer has a deterministic fallback for prose.
 */
class HealthServiceTest {

    private static HealthService service(String repo, GhClient gh, JevService jev, OpusProseClient opus) {
        HealthService s = new HealthService();
        s.repo = Optional.ofNullable(repo);
        s.gh = gh;
        s.jev = jev;
        s.opus = opus;
        s.mapper = new ObjectMapper();
        s.timeoutSeconds = 5;
        return s;
    }

    private static GhClient failingGh(String message) {
        return new GhClient() {
            @Override
            public String run(String... args) {
                throw new GhException(message);
            }
        };
    }

    private static GhClient workingGh() {
        return new GhClient() {
            @Override
            public String run(String... args) {
                return "{\"resources\":{}}";
            }
        };
    }

    private static JevService jev(boolean configured) {
        return new JevService() {
            @Override
            public boolean isConfigured() {
                return configured;
            }

            @Override
            public String modelVersion() {
                return "jev-test";
            }

            @Override
            public Uni<JevResponse> evaluate(Object state, JsonNode questions) {
                return Uni.createFrom().item(
                        new JevResponse("jev-probe-model", Map.of(), null));
            }
        };
    }

    /** {@code apiKey} is package-private in the client, so configuration is faked through its API. */
    private static OpusProseClient opus(boolean configured) {
        return new OpusProseClient() {
            @Override
            public boolean isConfigured() {
                return configured;
            }

            @Override
            public String modelVersion() {
                return "opus-test";
            }

            @Override
            public boolean reachable() {
                return true;
            }
        };
    }

    private static DependencyHealth byName(HealthReport report, String name) {
        return report.dependencies().stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no probe reported for " + name));
    }

    @Test
    void reportsEveryDependencyAsNotConfiguredWhenNothingIsSetUp() {
        HealthReport report = service(null, failingGh("gh: not found"), jev(false), opus(false)).check();

        assertEquals(3, report.dependencies().size());
        for (DependencyHealth d : report.dependencies()) {
            assertEquals(DependencyHealth.Status.NOT_CONFIGURED, d.status(), d.name() + ": " + d.detail());
        }
        assertFalse(report.isHealthy(), "gh and jev are required — an unconfigured agent cannot review");
    }

    @Test
    void namesTheMissingEnvironmentVariableSoTheFixIsObvious() {
        HealthReport report = service("  ", failingGh("x"), jev(false), opus(false)).check();

        assertTrue(byName(report, "gh").detail().contains("GH_REPO"));
        assertTrue(byName(report, "jev").detail().contains("TYPESAFE_API_KEY"));
        assertTrue(byName(report, "anthropic").detail().contains("ANTHROPIC_API_KEY"));
    }

    @Test
    void aMissingOpusKeyDegradesTheReportButNeverBlocksIt() {
        HealthReport report = service("quarkusio/quarkus", workingGh(), jev(true), opus(false)).check();

        DependencyHealth anthropic = byName(report, "anthropic");
        assertEquals(DependencyHealth.Status.NOT_CONFIGURED, anthropic.status());
        assertFalse(anthropic.required(), "Opus is optional by design — prose has a deterministic fallback");
        assertFalse(anthropic.isBlocking());
        assertTrue(report.isHealthy(), "both required dependencies answered");
        assertTrue(report.isDegraded(), "but the report must still say something is missing");
    }

    @Test
    void reportsTheProbedRepoAndTheModelTheEngineAnswersWith() {
        HealthReport report = service("quarkusio/quarkus", workingGh(), jev(true), opus(false)).check();

        assertEquals(DependencyHealth.Status.OK, byName(report, "gh").status());
        assertTrue(byName(report, "gh").detail().contains("quarkusio/quarkus"));
        assertEquals(DependencyHealth.Status.OK, byName(report, "jev").status());
        assertTrue(byName(report, "jev").detail().contains("jev-probe-model"),
                "the model the API actually answered with, not the configured alias");
    }

    @Test
    void aConfiguredButFailingDependencyIsUnreachableNotUnconfigured() {
        HealthReport report =
                service("quarkusio/quarkus", failingGh("gh failed (4): Bad credentials"), jev(true), opus(false))
                        .check();

        DependencyHealth ghHealth = byName(report, "gh");
        assertEquals(DependencyHealth.Status.UNREACHABLE, ghHealth.status());
        assertTrue(ghHealth.detail().contains("Bad credentials"), "the real failure text reaches the operator");
        assertTrue(ghHealth.isBlocking());
        assertFalse(report.isHealthy());
    }

    @Test
    void probesJevWithOneWellFormedNoulQuestion() {
        List<JsonNode> asked = new java.util.ArrayList<>();
        JevService recording = new JevService() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public Uni<JevResponse> evaluate(Object state, JsonNode questions) {
                asked.add(questions);
                return Uni.createFrom().item(new JevResponse("m", Map.of(), null));
            }
        };
        service("quarkusio/quarkus", workingGh(), recording, opus(false)).check();

        assertEquals(1, asked.size());
        JsonNode questions = asked.get(0);
        assertEquals(1, questions.size(), "a health probe must be the cheapest possible call");
        assertEquals("noul", questions.get("health_probe").get("type").asText());
    }
}
