package io.quarkus.prreview.it.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * Stands in for {@code jev}: a WireMock server on localhost that answers every submission with fixed answers.
 * <p>
 * The test owns it for the whole class and points the app at it with a system property. A
 * {@code QuarkusTestResourceLifecycleManager} doesn't fit: under {@code @QuarkusMainTest} it is started and stopped
 * around each launch, in the app's class loader, so the test could not inspect what was posted.
 */
public final class JevWireMock implements AutoCloseable {

    static final String BASE_URL_PROPERTY = "quarkus.langchain4j.typesafe.base-url";
    static final String PATH = "/v1/systemone";

    static final String ANSWERS = """
            {"model": "jev-test",
             "answers": {
               "test-adequacy.behaviour": {"type": "noul", "noul": 0.88},
               "test-adequacy.tests": {"type": "choice", "choice": "no-test-changes",
                 "probabilities": {"no-test-changes": 0.64, "multiple-gaps": 0.2, "undetermined": 0.16}}},
             "usage": {"input_tokens": 1234, "output_tokens": 56}}
            """;

    private final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /** Starts the server and points every later launch at it. */
    public static JevWireMock start() {
        JevWireMock jev = new JevWireMock();
        jev.server.start();
        jev.server.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(ANSWERS)));
        System.setProperty(BASE_URL_PROPERTY, jev.server.baseUrl());
        return jev;
    }

    /** The submissions received since the last {@link #reset()}. */
    public List<LoggedRequest> posts() {
        return server.findAll(postRequestedFor(urlEqualTo(PATH)));
    }

    public void reset() {
        server.resetRequests();
    }

    @Override
    public void close() {
        System.clearProperty(BASE_URL_PROPERTY);
        server.stop();
    }
}
