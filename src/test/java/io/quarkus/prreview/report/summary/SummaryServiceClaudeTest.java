package io.quarkus.prreview.report.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.model.Classification;
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
    void anAnswerIsOneRequest() {
        Summary summary = service.summarize(SummaryServiceTest.classification());

        assertEquals(ClaudeWireMock.SUMMARY, summary.text());
        assertEquals(1, claude.posts().size());
    }

    @Test
    void aTimedOutCallIsNotRetriedInTheBackground() throws InterruptedException {
        claude.delay(Duration.ofSeconds(2));
        Classification classification = SummaryServiceTest.classification();

        assertEquals(FallbackSummary.of(classification), service.summarize(classification));
        // Long enough for a retry to have been sent.
        Thread.sleep(3000);
        assertEquals(1, claude.posts().size());
    }

    @Test
    void aFailedCallIsNotRetried() {
        claude.failWith500();
        Classification classification = SummaryServiceTest.classification();

        assertEquals(FallbackSummary.of(classification), service.summarize(classification));
        assertEquals(1, claude.posts().size());
    }
}
