package io.quarkus.prreview.report.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.langchain4j.service.Result;

import dev.langchain4j.model.output.TokenUsage;
import io.quarkus.prreview.classification.checks.testadequacy.TestAdequacyCheck;
import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.clients.claude.ClassificationSummarizer;
import io.quarkus.prreview.pr.model.ChangedFile;
import io.quarkus.prreview.pr.model.PrContext;
import io.quarkus.prreview.pr.model.PullRequest;
import io.quarkus.prreview.report.summary.model.Summary;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class SummaryServiceTest {

    @InjectMock
    ClassificationSummarizer summarizer;

    @Inject
    SummaryService service;

    static Classification classification() {
        PullRequest pr = new PullRequest(57720, "Fix the thing", "https://github.com/quarkusio/quarkus/pull/57720",
                "OPEN", "diff --git a/SECRET-DIFF b/SECRET-DIFF");
        List<ChangedFile> files = List.of(
                new ChangedFile("core/src/main/java/Foo.java", ChangedFile.Kind.MODIFIED, null, false),
                new ChangedFile("core/src/test/java/FooTest.java", ChangedFile.Kind.ADDED, null, false));
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("some", 0.6123456789);
        probabilities.put("all", 0.3876543211);
        CheckResult check = new CheckResult("test-adequacy", List.of(
                new QuestionResult("behaviour", TestAdequacyCheck.BEHAVIOUR.text(),
                        new QuestionResult.YesNo(0.876543219)),
                new QuestionResult("tests-changed",
                        TestAdequacyCheck.TESTS_CHANGED.text(), new QuestionResult.YesNo(0.97)),
                new QuestionResult("reach", TestAdequacyCheck.REACH.text(),
                        new QuestionResult.Choice("some", TestAdequacyCheck.REACH.options(), probabilities)),
                new QuestionResult("assert", TestAdequacyCheck.ASSERTS.text(),
                        new QuestionResult.Choice("all", TestAdequacyCheck.ASSERTS.options(),
                                Map.of("all", 0.66)))));
        return new Classification(new PrContext(pr, files), List.of(check), "jev-1", new TokenUsage(1200, 40), 5000,
                60000);
    }

    static Result<String> result(String text) {
        return Result.<String>builder().content(text).tokenUsage(new TokenUsage(321, 45)).build();
    }

    @Test
    void claudeAnswerCarriesTheClaudeSourceAndSeesOnlyAnswersAndFiles() {
        when(summarizer.summarize(anyString())).thenReturn(result("  jev sees a behaviour change.  "));

        Summary summary = service.summarize(classification());

        assertEquals("jev sees a behaviour change.", summary.text());
        assertEquals("Claude (claude-opus-5-5)", summary.source());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(summarizer).summarize(message.capture());
        String sent = message.getValue();
        assertTrue(sent.startsWith("jev's answers:\n"), sent);
        assertTrue(sent.contains("behaviour (" + TestAdequacyCheck.BEHAVIOUR.text() + ")"), sent);
        assertTrue(sent.contains("0.876543219"), sent);
        assertTrue(sent.contains("tests-changed (" + TestAdequacyCheck.TESTS_CHANGED.text() + ")"), sent);
        assertTrue(sent.contains("chosen option some"), sent);
        assertTrue(sent.contains("some 0.6123456789, all 0.3876543211"), sent);
        assertTrue(sent.contains("chosen option all"), sent);
        assertTrue(sent.endsWith("Changed files:\n- core/src/main/java/Foo.java (modified)\n"
                + "- core/src/test/java/FooTest.java (added)\n"), sent);
        assertFalse(sent.contains("Fix the thing"), sent);
        assertFalse(sent.contains("SECRET-DIFF"), sent);

        // The reported size is exactly what was sent; the tokens are Claude's.
        assertEquals(ClassificationSummarizer.SYSTEM.length() + sent.length(), summary.claudeChars());
        assertEquals(new TokenUsage(321, 45), summary.claudeTokens());
    }

    @Test
    void aFailedCallGivesTheExactTemplateAndKeepsTheSizeSent() {
        when(summarizer.summarize(anyString())).thenThrow(new RuntimeException("HTTP 500"));

        Summary summary = service.summarize(classification());

        assertEquals(FallbackSummary.of(classification()).text(), summary.text());
        assertEquals("jev classification: behaviour 0.88; tests-changed 0.97; reach some 0.61; assert all 0.66.",
                summary.text());
        assertEquals("fallback template", summary.source());
        assertEquals(ClassificationSummarizer.SYSTEM.length()
                + SummaryService.userMessage(classification()).length(), summary.claudeChars());
        assertNull(summary.claudeTokens());
    }

    @Test
    void blankTextGivesTheExactTemplate() {
        when(summarizer.summarize(anyString())).thenReturn(result(" \n "));

        Summary summary = service.summarize(classification());

        assertEquals(FallbackSummary.of(classification()).text(), summary.text());
        assertEquals("fallback template", summary.source());
        assertEquals(new TokenUsage(321, 45), summary.claudeTokens());
    }

    @Test
    void theClassificationIsTheSameWithAndWithoutClaude() {
        Classification classification = classification();
        Classification before = classification();

        when(summarizer.summarize(anyString())).thenReturn(result("A summary."));
        service.summarize(classification);
        assertEquals(before, classification);

        when(summarizer.summarize(anyString())).thenThrow(new RuntimeException("down"));
        service.summarize(classification);
        assertEquals(before, classification);
    }
}
