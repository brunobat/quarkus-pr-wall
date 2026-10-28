package io.quarkus.prreview.report.summary;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.clients.claude.ClassificationSummarizer;
import io.quarkus.prreview.pr.model.ChangedFile;
import io.quarkus.prreview.report.AnswerFormat;
import io.quarkus.prreview.report.summary.model.Summary;

/**
 * Describes a classification in prose. Claude writes it; the {@link FallbackSummary} template is used instead when
 * no Claude key is set, the call fails, no answer arrives within the Claude timeout or the text is blank. The
 * classification itself is never changed.
 */
@ApplicationScoped
public class SummaryService {

    private static final Logger LOG = Logger.getLogger(SummaryService.class);

    /** The key's value when {@code ANTHROPIC_API_KEY} is not set, see {@code application.yml}. */
    private static final String NO_KEY = "not-set";

    private static final Executor CLAUDE_CALLS = task -> Thread.ofVirtual().name("claude-summary").start(task);

    private final ClassificationSummarizer summarizer;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public SummaryService(ClassificationSummarizer summarizer,
            @ConfigProperty(name = "quarkus.langchain4j.anthropic.api-key") String apiKey,
            @ConfigProperty(name = "quarkus.langchain4j.anthropic.chat-model.model-name") String model,
            @ConfigProperty(name = "quarkus.langchain4j.anthropic.timeout") Duration timeout) {
        this.summarizer = summarizer;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
    }

    /** Call only with a classification built from a successful {@code jev} answer. */
    public Summary summarize(Classification classification) {
        int pr = classification.pr().pullRequest().number();
        if (NO_KEY.equals(apiKey)) {
            LOG.warnf("PR #%d: no ANTHROPIC_API_KEY set, using the fallback summary", pr);
            return FallbackSummary.of(classification);
        }
        String answers = answers(classification);
        String changedFiles = changedFiles(classification);
        // The extension retries a failed attempt once and cannot be set to zero retries, so the whole call, retry
        // included, gets the Claude timeout: that is the longest the summary can delay a PR.
        CompletableFuture<String> call = CompletableFuture.supplyAsync(
                () -> summarizer.summarize(answers, changedFiles), CLAUDE_CALLS);
        String text;
        try {
            text = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            LOG.warnf("PR #%d: no Claude summary within %s, using the fallback summary", pr, timeout);
            return FallbackSummary.of(classification);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            LOG.warnf("PR #%d: Claude summary failed (%s), using the fallback summary", pr,
                    cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage());
            return FallbackSummary.of(classification);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            LOG.warnf("PR #%d: interrupted while waiting for the Claude summary, using the fallback summary", pr);
            return FallbackSummary.of(classification);
        }
        if (text == null || text.isBlank()) {
            LOG.warnf("PR #%d: Claude returned a blank summary, using the fallback summary", pr);
            return FallbackSummary.of(classification);
        }
        return new Summary(text.strip(), model);
    }

    /** Every question with its text and {@code jev}'s answer, probabilities unrounded. */
    static String answers(Classification classification) {
        List<String> lines = new ArrayList<>();
        for (CheckResult check : classification.checks()) {
            for (QuestionResult question : check.questions()) {
                lines.add("- " + question.label() + " (" + question.text() + "): " + answer(question.answer()));
            }
        }
        return String.join("\n", lines);
    }

    private static String answer(QuestionResult.Answer answer) {
        return switch (answer) {
            case QuestionResult.YesNo yesNo -> "probability of yes " + AnswerFormat.exact(yesNo.probability());
            case QuestionResult.Choice choice -> {
                List<String> reported = new ArrayList<>();
                for (Map.Entry<String, Double> p : choice.probabilities().entrySet()) {
                    reported.add(p.getKey() + " " + AnswerFormat.exact(p.getValue()));
                }
                yield "chosen option " + choice.value()
                        + (reported.isEmpty() ? "" : "; option probabilities: " + String.join(", ", reported));
            }
        };
    }

    static String changedFiles(Classification classification) {
        List<String> lines = new ArrayList<>();
        for (ChangedFile file : classification.pr().changedFiles()) {
            lines.add("- " + file.path() + " (" + file.describe() + ")");
        }
        return lines.isEmpty() ? "(none)" : String.join("\n", lines);
    }
}
