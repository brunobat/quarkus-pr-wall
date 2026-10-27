package io.quarkus.prreview.clients.jev;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.request.DecisionRequestParameters;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import dev.langchain4j.model.typesafe.TypeSafeDecisionModel;
import io.quarkus.prreview.clients.jev.model.JevDecision;
import io.quarkus.prreview.config.ReviewConfig;

class JevClientTest {

    static final DecisionRequest REQUEST = DecisionRequest.builder()
            .input("Changed files:\n- a.java (modified)\n\nUnified diff:\ndiff --git a/a.java b/a.java\n\"quoted\" é\n")
            .question("c.q", YesNoQuestion.of("Q?"))
            .build();

    private WireMockServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void submissionAtTheLimitIsSentOnce() {
        DecisionModel model = mock(DecisionModel.class);
        DecisionResponse response = DecisionResponse.builder().answer("c.q", YesNoAnswer.of(0.5)).build();
        when(model.decide(any(DecisionRequest.class))).thenReturn(response);
        int size = sizeWithModel("jev-latest");

        JevDecision decision = new JevClient(model, config(size), "jev-latest").decide(REQUEST);

        assertSame(response, decision.response());
        assertEquals(size, decision.submissionChars());
        assertEquals(size, decision.limit());
        ArgumentCaptor<DecisionRequest> sent = ArgumentCaptor.forClass(DecisionRequest.class);
        verify(model).decide(sent.capture());
        assertEquals("jev-latest", sent.getValue().modelName());
    }

    @Test
    void submissionOverTheLimitIsNotSent() {
        DecisionModel model = mock(DecisionModel.class);
        int size = sizeWithModel("jev-latest");

        JevException e = assertThrows(JevException.class,
                () -> new JevClient(model, config(size - 1), "jev-latest").decide(REQUEST));

        assertEquals("submission is %d characters, over the limit of %d".formatted(size, size - 1), e.getMessage());
        assertEquals(size, e.submissionChars());
        assertEquals(size - 1, e.limit());
        verify(model, never()).decide(any(DecisionRequest.class));
    }

    @Test
    void modelFailureKeepsJevsMessage() {
        DecisionModel model = mock(DecisionModel.class);
        when(model.decide(any(DecisionRequest.class)))
                .thenThrow(new InvalidRequestException("{\"error\":\"max_tokens_exceeded\"}"));

        JevException e = assertThrows(JevException.class,
                () -> new JevClient(model, config(Integer.MAX_VALUE), "jev-latest").decide(REQUEST));

        assertTrue(e.getMessage().contains("max_tokens_exceeded"), e.getMessage());
        assertEquals(sizeWithModel("jev-latest"), e.submissionChars());
    }

    /**
     * The measured size equals the length of the body the real TypeSafe model posts. The JDK HTTP client is
     * named because two client factories are on the classpath outside Quarkus.
     */
    @Test
    void measuredSizeEqualsTheBodyPosted() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withHeader("Content-Type",
                "application/json").withBody("{\"model\":\"jev-x\",\"answers\":{\"c.q\":{\"type\":\"noul\",\"noul\":0.5}}}")));
        DecisionModel model = TypeSafeDecisionModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()).baseUrl(server.baseUrl()).apiKey("k").modelName("unused")
                .build();

        JevDecision decision = new JevClient(model, config(Integer.MAX_VALUE), "jev-x").decide(REQUEST);

        String body = server.findAll(postRequestedFor(urlEqualTo("/v1/systemone"))).get(0).getBodyAsString();
        assertTrue(body.contains("\"model\":\"jev-x\""), body);
        assertEquals(body.length(), decision.submissionChars(), body);
    }

    private static int sizeWithModel(String modelName) {
        return SubmissionSize.of(REQUEST.toBuilder()
                .parameters(DecisionRequestParameters.builder()
                        .modelName(modelName).build())
                .build());
    }

    static ReviewConfig config(int limit) {
        return new ReviewConfig() {
            @Override
            public Gh gh() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Jev jev() {
                return () -> limit;
            }

            @Override
            public Path outputDir() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
