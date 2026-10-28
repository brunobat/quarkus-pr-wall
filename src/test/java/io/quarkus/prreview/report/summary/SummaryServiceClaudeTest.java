package io.quarkus.prreview.report.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.clients.claude.ClassificationSummarizer;
import io.quarkus.prreview.report.summary.model.Summary;
import io.quarkus.prreview.it.support.ClaudeWireMock;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The real Claude client against the stub, with the app kept running, so a call left in the background (such as a
 * retry after the timeout) would show up as a second request.
 */
@QuarkusTest
@WithTestResource(SummaryServiceClaudeTest.Claude.class)
class SummaryServiceClaudeTest {

    public static class Claude implements QuarkusTestResourceLifecycleManager {

        private ClaudeWireMock claude;

        @Override
        public Map<String, String> start() {
            claude = ClaudeWireMock.startServer();
            // Profile-qualified, so it outranks the test profile's base URL in application.yml.
            return Map.of("%test.quarkus.langchain4j.anthropic.base-url", claude.baseUrl(),
                    "quarkus.langchain4j.anthropic.timeout", "1s");
        }

        @Override
        public void inject(TestInjector testInjector) {
            testInjector.injectIntoFields(claude, new TestInjector.MatchesType(ClaudeWireMock.class));
        }

        @Override
        public void stop() {
            claude.close();
        }
    }

    ClaudeWireMock claude;

    @Inject
    SummaryService service;

    @AfterEach
    void reset() {
        claude.reset();
    }

    @Test
    void anAnswerIsOneRequestAndItsSizeIsWhatClaudeReceived() {
        Summary summary = service.summarize(SummaryServiceTest.classification());

        assertEquals(ClaudeWireMock.SUMMARY, summary.text());
        assertEquals(1, claude.posts().size());
        JsonNode body = JsonMapper.shared().readTree(claude.posts().getFirst().getBodyAsString());
        String received = text(body.get("system")) + text(body.get("messages").get(0).get("content"));
        assertEquals(received.length(), summary.claudeChars(), received);
        assertEquals(100, summary.claudeTokens().inputTokenCount());
        assertEquals(20, summary.claudeTokens().outputTokenCount());
    }

    /** A message's text, whether sent as a string or as content blocks. */
    private static String text(JsonNode node) {
        if (node.isString()) {
            return node.asString();
        }
        StringBuilder text = new StringBuilder();
        node.forEach(block -> text.append(block.get("text").asString()));
        return text.toString();
    }

    @Test
    void aTimedOutCallIsNotRetriedInTheBackground() throws InterruptedException {
        claude.delay(Duration.ofSeconds(2));
        Classification classification = SummaryServiceTest.classification();

        assertEquals(FallbackSummary.of(classification).text(), service.summarize(classification).text());
        // Long enough for a retry to have been sent.
        Thread.sleep(3000);
        assertEquals(1, claude.posts().size());
    }

    @Test
    void aFailedCallIsNotRetried() {
        claude.failWith500();
        Classification classification = SummaryServiceTest.classification();

        Summary summary = service.summarize(classification);

        assertEquals(FallbackSummary.of(classification).text(), summary.text());
        assertEquals(1, claude.posts().size());
        // The size of what was sent is kept; Claude reported no tokens.
        assertEquals(ClassificationSummarizer.SYSTEM.length()
                + SummaryService.userMessage(classification).length(), summary.claudeChars());
        assertNull(summary.claudeTokens());
    }
}
