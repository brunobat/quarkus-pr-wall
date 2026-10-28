package io.quarkus.prreview.report.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.clients.claude.ClassificationSummarizer;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(SummaryServiceTimeoutTest.TwoSecondTimeout.class)
class SummaryServiceTimeoutTest {

    public static class TwoSecondTimeout implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.langchain4j.anthropic.timeout", "2s");
        }
    }

    @InjectMock
    ClassificationSummarizer summarizer;

    @Inject
    SummaryService service;

    @Test
    void theTimeoutBoundsTheWholeCallAndStopsIt() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        // Stands in for a Claude call that outlasts the timeout.
        when(summarizer.summarize(anyString(), anyString())).thenAnswer(invocation -> {
            try {
                Thread.sleep(30_000);
                return "too late";
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        });
        Classification classification = SummaryServiceTest.classification();

        long start = System.nanoTime();
        var summary = service.summarize(classification);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(FallbackSummary.of(classification), summary);
        // One timeout, with room for a slow machine, but well short of two (4 s).
        assertTrue(took.compareTo(Duration.ofMillis(3500)) < 0, "took " + took);
        // The call's thread is interrupted. That the real client then ends the call, with no retry, is checked in
        // SummaryServiceClaudeTest.
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the Claude call was not interrupted");
    }
}
