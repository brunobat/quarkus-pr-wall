package io.quarkus.prreview.facts.services;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Test-profile replacement for {@link GhClient} that makes any {@code gh} invocation fail loudly instead of
 * shelling out to the real GitHub. {@link GhClient} is the single chokepoint for all GitHub access (the only
 * place the agent runs a subprocess), so overriding it here guarantees the whole suite — including any
 * {@code @QuarkusTest} that wires up {@code FactsService}/{@code ReviewEngine} — cannot reach the network.
 *
 * <p>A test that genuinely needs PR facts installs a {@link GhFixture} of canned {@code gh} output (the eval
 * suite does this, so the real facts layer runs over a recorded diff). With no fixture installed the call
 * still fails loudly — the no-network guarantee is the default, opting out of it is explicit and scoped.
 */
@Mock
@ApplicationScoped
public class NoNetworkGhClient extends GhClient {

    /** Canned replacement for one {@code gh} invocation; receives the argv the agent would have run. */
    @FunctionalInterface
    public interface GhFixture {
        String run(String... args);
    }

    private static volatile GhFixture fixture;

    /** Serve {@code gh} calls from {@code f} until {@link #clear()}. Always clear in an {@code @AfterEach}. */
    public static void install(GhFixture f) {
        fixture = f;
    }

    public static void clear() {
        fixture = null;
    }

    @Override
    public String run(String... args) {
        GhFixture f = fixture;
        if (f != null) {
            return f.run(args);
        }
        throw new AssertionError(
                "Tests must not call the real GitHub. Blocked gh invocation: gh " + String.join(" ", args));
    }
}
