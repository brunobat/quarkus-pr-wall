package io.quarkus.prreview.report.summary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * no Claude key is set, the call fails or the text is blank. The classification itself is never changed.
 */
@ApplicationScoped
public class SummaryService {

    private static final Logger LOG = Logger.getLogger(SummaryService.class);

    /** The extension's own "not configured" value, see {@code application.yml}. */
    private static final String NO_KEY = "dummy";

    private final ClassificationSummarizer summarizer;
    private final String apiKey;
    private final String model;

    public SummaryService(ClassificationSummarizer summarizer,
            @ConfigProperty(name = "quarkus.langchain4j.anthropic.api-key") String apiKey,
            @ConfigProperty(name = "quarkus.langchain4j.anthropic.chat-model.model-name") String model) {
        this.summarizer = summarizer;
        this.apiKey = apiKey;
        this.model = model;
    }

    /** Call only with a classification built from a successful {@code jev} answer. */
    public Summary summarize(Classification classification) {
        int pr = classification.pr().pullRequest().number();
        if (NO_KEY.equals(apiKey)) {
            LOG.warnf("PR #%d: no ANTHROPIC_API_KEY set, using the fallback summary", pr);
            return FallbackSummary.of(classification);
        }
        String text;
        try {
            text = summarizer.summarize(answers(classification), changedFiles(classification));
        } catch (RuntimeException e) {
            LOG.warnf("PR #%d: Claude summary failed (%s), using the fallback summary", pr,
                    e.getMessage() == null ? e.getClass().getName() : e.getMessage());
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
