package io.quarkus.prreview.it.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
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
               "test-adequacy.tests-changed": {"type": "noul", "noul": 0.08},
               "test-adequacy.reach": {"type": "choice", "choice": "no-test-changes",
                 "probabilities": {"no-test-changes": 0.83, "some": 0.1, "undetermined": 0.07}},
               "test-adequacy.assert": {"type": "choice", "choice": "no-test-changes",
                 "probabilities": {"no-test-changes": 0.88, "none": 0.12}}},
             "usage": {"input_tokens": 1234, "output_tokens": 56}}
            """;

    private final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /** Starts the server and points every later launch at it. */
    public static JevWireMock start() {
        JevWireMock jev = new JevWireMock();
        jev.server.start();
        jev.answer();
        System.setProperty(BASE_URL_PROPERTY, jev.server.baseUrl());
        return jev;
    }

    /** Until {@link #reset()}, a submission whose body contains {@code text} gets this error instead of answers. */
    public void failWhenBodyContains(String text, int status, String body) {
        server.stubFor(post(urlEqualTo(PATH)).atPriority(1).withRequestBody(containing(text))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void answer() {
        server.stubFor(post(urlEqualTo(PATH)).atPriority(5)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(ANSWERS)));
    }

    /** The submissions received since the last {@link #reset()}. */
    public List<LoggedRequest> posts() {
        return server.findAll(postRequestedFor(urlEqualTo(PATH)));
    }

    /** Forgets the received submissions and answers every submission again. */
    public void reset() {
        server.resetAll();
        answer();
    }

    @Override
    public void close() {
        System.clearProperty(BASE_URL_PROPERTY);
        server.stop();
    }
}
