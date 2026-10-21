package io.quarkus.prreview.jev.services;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.prreview.jev.model.JevResponse;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/**
 * Serves {@code jev} answers from a fixture instead of the network, for the eval suite (plan §10, §13).
 *
 * <p>This is a plain {@code @Alternative} rather than a {@code @Mock}, so it is only active for tests that
 * select it via {@code QuarkusTestProfile.getEnabledAlternatives()} — see {@code EvalProfile}.
 * {@link JevServiceWireMockTest} needs the <em>real</em> {@link JevService}; overriding the method would
 * also drop its {@code @Retry}/{@code @CircuitBreaker} interceptor bindings, which is precisely what that
 * test exercises. Deliberately <strong>no</strong> {@code @Priority}: an {@code @Alternative} carrying one
 * is a globally enabled alternative, which would silently serve fixtures to the WireMock test too.
 *
 * <p>The fixture is a static because a review fans its pipelines out across Mutiny worker threads, so a
 * {@code ThreadLocal} would not be visible where the call actually happens.
 */
@Alternative
@ApplicationScoped
public class FixtureJevService extends JevService {

    /** Produces the canned response for one evaluation, given the question block being asked. */
    @FunctionalInterface
    public interface JevFixture {
        JevResponse answer(JsonNode questions);
    }

    private static volatile JevFixture fixture;

    public static void install(JevFixture f) {
        fixture = f;
    }

    public static void clear() {
        fixture = null;
    }

    @Override
    public Uni<JevResponse> evaluate(Object state, JsonNode questions) {
        JevFixture f = fixture;
        if (f == null) {
            throw new AssertionError("No jev fixture installed — an eval must not reach the real API");
        }
        return Uni.createFrom().item(f.answer(questions));
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String modelVersion() {
        return "jev-fixture";
    }
}
