package io.quarkus.prreview.jev.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.quarkus.prreview.jev.client.JevException;
import io.quarkus.prreview.jev.model.Answer;
import io.quarkus.prreview.jev.model.JevResponse;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@code jev} fault-tolerance policy against a real HTTP server (plan §10, §13). The
 * policy is the whole point of {@link JevService}, and until now nothing proved that a 429 retries,
 * that a 422 does <em>not</em>, or that the exception mapper classifies either correctly.
 *
 * <p>Each test asserts the observable request count, not just the outcome: "aborts immediately" is a
 * claim about how many times we hit the API, and a passing outcome assertion alone would not catch a
 * 422 being quietly retried four times.
 */
@QuarkusTest
@QuarkusTestResource(JevWireMockResource.class)
class JevServiceWireMockTest {

    private static final String PATH = "/v1/systemone";

    @Inject
    JevService jev;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reset() {
        WireMock.configureFor("localhost", JevWireMockResource.server().port());
        WireMock.reset();
    }

    @Test
    void happyPathParsesEveryAnswerType() {
        stubJson(WireMock.post(PATH), 200, fixture("response-200.json"));

        JevResponse response = evaluate();

        assertEquals("jev-latest", response.model());
        assertEquals(3, response.answers().size());

        Answer choice = response.answer("Q1");
        assertEquals("bugfix", choice.choice());
        assertEquals(0.91, choice.derivedConfidence(), 1e-9);

        Answer score = response.answer("Q2");
        assertEquals(2.4, score.score(), 1e-9);
        assertEquals(0.78, score.derivedConfidence(), 1e-9);

        // noul carries no confidence field — it is derived as |2·noul − 1| (plan §2, §6).
        Answer noul = response.answer("Q3");
        assertTrue(noul.isNoul());
        assertEquals(0.97, noul.noulValue(), 1e-9);
        assertEquals(0.94, noul.derivedConfidence(), 1e-9);

        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo(PATH)));
    }

    @Test
    void sendsBearerTokenAndTheQuestionBlockVerbatim() {
        stubJson(WireMock.post(PATH), 200, fixture("response-200.json"));

        evaluate();

        WireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo(PATH))
                .withHeader("Authorization", WireMock.equalTo("Bearer test-key"))
                .withRequestBody(WireMock.matchingJsonPath("$.model"))
                .withRequestBody(WireMock.matchingJsonPath("$.state.text"))
                .withRequestBody(WireMock.matchingJsonPath("$.questions.Q1.type",
                        WireMock.equalTo("noul"))));
    }

    @Test
    void retriesOn429ThenSucceeds() {
        twoFailuresThenSuccess(429, "response-429.json");

        assertEquals("jev-latest", evaluate().model());
        WireMock.verify(3, WireMock.postRequestedFor(WireMock.urlEqualTo(PATH)));
    }

    @Test
    void retriesOn529ThenSucceeds() {
        twoFailuresThenSuccess(529, "response-529.json");

        assertEquals("jev-latest", evaluate().model());
        WireMock.verify(3, WireMock.postRequestedFor(WireMock.urlEqualTo(PATH)));
    }

    @Test
    void givesUpAfterFourRetriesOnSustained429() {
        stubJson(WireMock.post(PATH), 429, fixture("response-429.json"));

        JevException.Retryable e = assertThrows(JevException.Retryable.class, this::evaluate);

        assertEquals(429, e.status());
        // maxRetries = 4 ⇒ the initial attempt plus four retries, then the last failure surfaces.
        WireMock.verify(5, WireMock.postRequestedFor(WireMock.urlEqualTo(PATH)));
    }

    @Test
    void failsFastOn422WithoutRetrying() {
        stubJson(WireMock.post(PATH), 422, fixture("response-422.json"));

        JevException.Fatal e = assertThrows(JevException.Fatal.class, this::evaluate);

        assertEquals(422, e.status());
        assertTrue(e.getMessage().contains("fix the request"), e.getMessage());
        // A 422 is a bug in our own question JSON: retrying it only burns quota (plan §10).
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo(PATH)));
    }

    @Test
    void failsFastOn401WithoutRetrying() {
        stubJson(WireMock.post(PATH), 401, fixture("response-401.json"));

        JevException.Fatal e = assertThrows(JevException.Fatal.class, this::evaluate);

        assertEquals(401, e.status());
        assertTrue(e.getMessage().contains("TYPESAFE_API_KEY"), e.getMessage());
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo(PATH)));
    }

    @Test
    void treatsAnUnmappedServerErrorAsFatal() {
        stubJson(WireMock.post(PATH), 500, "{\"error\":\"boom\"}");

        JevException e = assertThrows(JevException.class, this::evaluate);

        assertInstanceOf(JevException.Fatal.class, e);
        assertEquals(500, ((JevException.Fatal) e).status());
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo(PATH)));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private JevResponse evaluate() {
        return jev.evaluate(Map.of("text", "a state"), questions()).await().atMost(Duration.ofSeconds(30));
    }

    private JsonNode questions() {
        try {
            return mapper.readTree("""
                    {"Q1": {"type": "noul", "instructions": "the change is a bugfix"}}""");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Two failures of {@code status}, then a 200 — the shape the retry policy is meant to absorb. */
    private void twoFailuresThenSuccess(int status, String errorFixture) {
        String scenario = "retry-" + status;
        stubJson(WireMock.post(PATH).inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("second"), status, fixture(errorFixture));
        stubJson(WireMock.post(PATH).inScenario(scenario)
                .whenScenarioStateIs("second").willSetStateTo("ok"), status, fixture(errorFixture));
        stubJson(WireMock.post(PATH).inScenario(scenario)
                .whenScenarioStateIs("ok"), 200, fixture("response-200.json"));
    }

    private static void stubJson(com.github.tomakehurst.wiremock.client.MappingBuilder builder,
            int status, String body) {
        WireMock.stubFor(builder.willReturn(WireMock.aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private static String fixture(String name) {
        try (InputStream in = JevServiceWireMockTest.class.getClassLoader()
                .getResourceAsStream("jev/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture jev/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
