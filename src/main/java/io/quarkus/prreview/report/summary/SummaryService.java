package io.quarkus.prreview.report.summary;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.clients.claude.ClassificationSummarizer;
import io.quarkus.prreview.pr.model.ChangedFile;
import io.quarkus.prreview.report.AnswerFormat;
import io.quarkus.prreview.report.summary.model.Summary;
import io.quarkus.runtime.Startup;

/**
 * Describes a classification in prose. Claude writes it; the {@link FallbackSummary} template is used instead when
 * no Claude key is set, the call fails, no answer arrives within the Claude timeout or the text is blank. The
 * classification itself is never changed.
 * <p>
 * Created at startup, so a timeout that is not positive stops the run before any PR is processed (only when a
 * Claude key is set: without one, Claude and its timeout are not used).
 */
@Startup
@ApplicationScoped
public class SummaryService {

    private static final Logger LOG = Logger.getLogger(SummaryService.class);

    /** The key's value when {@code ANTHROPIC_API_KEY} is not set, see {@code application.yml}. */
    private static final String NO_KEY = "not-set";

    private final ClassificationSummarizer summarizer;
    private final ManagedExecutor executor;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public SummaryService(ClassificationSummarizer summarizer, ManagedExecutor executor,
            @ConfigProperty(name = "quarkus.langchain4j.anthropic.api-key") String apiKey,
            @ConfigProperty(name = "quarkus.langchain4j.anthropic.chat-model.model-name") String model,
            @ConfigProperty(name = "quarkus.langchain4j.anthropic.timeout") Duration timeout) {
        if (!NO_KEY.equals(apiKey) && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("ANTHROPIC_TIMEOUT must be positive, but is " + timeout);
        }
        this.summarizer = summarizer;
        this.executor = executor;
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
        String message = userMessage(classification);
        int chars = ClassificationSummarizer.SYSTEM.length() + message.length();
        // The Claude timeout is the longest the summary can delay a PR. The call makes one attempt (see NoRetries).
        // A Future from submit(), not a CompletableFuture, because only its cancel(true) interrupts the call at the
        // timeout; with no retry, that ends it.
        Future<Result<String>> call = executor.submit(() -> summarizer.summarize(message));
        Result<String> result;
        try {
            result = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            LOG.warnf("PR #%d: no Claude summary within %s, using the fallback summary", pr, timeout);
            return fallback(classification, chars);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            LOG.warnf("PR #%d: Claude summary failed (%s), using the fallback summary", pr,
                    cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage());
            return fallback(classification, chars);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            LOG.warnf("PR #%d: interrupted while waiting for the Claude summary, using the fallback summary", pr);
            return fallback(classification, chars);
        }
        String text = result == null ? null : result.content();
        TokenUsage tokens = result == null ? null : result.tokenUsage();
        if (text == null || text.isBlank()) {
            LOG.warnf("PR #%d: Claude returned a blank summary, using the fallback summary", pr);
            return new Summary(FallbackSummary.of(classification).text(), null, chars, tokens);
        }
        return new Summary(text.strip(), model, chars, tokens);
    }

    /** The template, recording that {@code chars} characters were sent to Claude without an answer. */
    private static Summary fallback(Classification classification, int chars) {
        return new Summary(FallbackSummary.of(classification).text(), null, chars, null);
    }

    /** What Claude is sent besides the system message: the answers and the changed files, nothing else. */
    static String userMessage(Classification classification) {
        return "jev's answers:\n" + answers(classification) + "\n\nChanged files:\n" + changedFiles(classification)
                + "\n";
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
