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
 * Stands in for the Anthropic Messages API: a WireMock server on localhost that answers with a fixed summary, or
 * with HTTP 500 after {@link #failWith500()}. Owned by the test for the whole class, like {@link JevWireMock}.
 */
public final class ClaudeWireMock implements AutoCloseable {

    static final String BASE_URL_PROPERTY = "quarkus.langchain4j.anthropic.base-url";
    static final String PATH = "/v1/messages";

    public static final String SUMMARY = "jev finds a likely runtime behaviour change and no changed tests.";

    static final String MESSAGE = """
            {"id": "msg_test", "type": "message", "role": "assistant", "model": "claude-test",
             "content": [{"type": "text", "text": "%s"}],
             "stop_reason": "end_turn", "usage": {"input_tokens": 100, "output_tokens": 20}}
            """.formatted(SUMMARY);

    private final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /** Starts the server and points every later launch at it; the base URL must end in {@code /v1/}. */
    public static ClaudeWireMock start() {
        ClaudeWireMock claude = new ClaudeWireMock();
        claude.server.start();
        claude.answer();
        System.setProperty(BASE_URL_PROPERTY, claude.server.baseUrl() + "/v1/");
        return claude;
    }

    /** From now on every request gets HTTP 500, until {@link #reset()}. */
    public void failWith500() {
        server.resetMappings();
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\": \"error\", \"error\": {\"type\": \"api_error\", \"message\": \"Internal error\"}}")));
    }

    /** The requests received since the last {@link #reset()}. */
    public List<LoggedRequest> posts() {
        return server.findAll(postRequestedFor(urlEqualTo(PATH)));
    }

    /** Forgets the received requests and answers normally again. */
    public void reset() {
        server.resetAll();
        answer();
    }

    private void answer() {
        server.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(MESSAGE)));
    }

    @Override
    public void close() {
        System.clearProperty(BASE_URL_PROPERTY);
        server.stop();
    }
}
