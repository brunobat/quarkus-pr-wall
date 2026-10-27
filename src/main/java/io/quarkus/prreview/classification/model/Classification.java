package io.quarkus.prreview.classification.model;

import java.util.List;

import dev.langchain4j.model.output.TokenUsage;
import io.quarkus.prreview.pr.model.PrContext;

/**
 * One PR's classification: {@code jev}'s answers, unchanged and grouped by check, with the run details shown in the
 * report footer.
 *
 * @param jevModel the model name {@code jev} reported
 * @param tokenUsage {@code jev}'s reported token usage, or {@code null} when not reported
 * @param submissionChars the measured submission size, in characters
 * @param limit the configured submission limit, in characters
 */
public record Classification(PrContext pr, List<CheckResult> checks, String jevModel, TokenUsage tokenUsage,
        int submissionChars, int limit) {
}
