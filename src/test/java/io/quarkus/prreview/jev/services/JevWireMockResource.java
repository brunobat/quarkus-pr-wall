package io.quarkus.prreview.jev.services;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * Starts a WireMock server standing in for the {@code jev} API and points the REST client at it
 * (plan §13). Config is handed back from {@link #start()} rather than written into
 * {@code src/test/resources}, so it overrides {@code application.yml} unambiguously and only for
 * tests that opt in.
 *
 * <p>Two fault-tolerance knobs are overridden for the duration:
 * <ul>
 *   <li>the retry delay drops to 10ms so a four-retry path takes milliseconds, not ~15s;</li>
 *   <li>the circuit breaker is disabled, because each retry attempt counts as an invocation against
 *       it and the deliberate failures here would otherwise trip it and leak across tests.</li>
 * </ul>
 * The retry <em>policy</em> under test (what retries, what aborts, how many times) is untouched.
 */
public class JevWireMockResource implements QuarkusTestResourceLifecycleManager {

    private static final String METHOD = "io.quarkus.prreview.jev.services.JevService/evaluate";

    private static WireMockServer server;

    /** The running server, for stubbing and request verification from a test. */
    public static WireMockServer server() {
        return server;
    }

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return Map.of(
                "quarkus.rest-client.jev.url", server.baseUrl(),
                "jev.api-key", "test-key",
                METHOD + "/Retry/delay", "10",
                METHOD + "/ExponentialBackoff/maxDelay", "100",
                METHOD + "/CircuitBreaker/enabled", "false");
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
