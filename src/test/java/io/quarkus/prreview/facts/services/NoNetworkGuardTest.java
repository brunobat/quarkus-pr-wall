package io.quarkus.prreview.facts.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the test suite's network isolation: under {@code @QuarkusTest} the injected {@link GhClient} must be the
 * {@link NoNetworkGhClient} mock, so any {@code gh}/GitHub call made from a test fails loudly instead of hitting
 * the live API. If someone removes the mock this test fails, flagging that the isolation has been lost.
 */
@QuarkusTest
class NoNetworkGuardTest {

    @Inject
    GhClient gh;

    @Test
    void ghClientIsTheNoNetworkMock() {
        AssertionError blocked = assertThrows(AssertionError.class, () -> gh.run("pr", "view", "1"));
        assertTrue(blocked.getMessage().contains("must not call the real GitHub"),
                "the injected GhClient must be the network-blocking test mock");
    }
}
