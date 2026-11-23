package io.quarkus.prreview.triage.model;

import java.util.List;

import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.JevResult;
import io.quarkus.prreview.classification.model.SourceResult;
import io.quarkus.prreview.report.summary.Summary;

/** Outcomes for rendering tests: {@code jev}'s result alone, with every GitHub source unavailable. */
public final class TestOutcomes {

    public static final String NOT_GATHERED = "not gathered";

    private TestOutcomes() {
    }

    public static PrOutcome answered(Classification classification, Summary summary) {
        var pr = classification.pr().pullRequest();
        return new PrOutcome(pr.number(), classification.checks().stream().map(CheckResult::checkId).toList(), pr,
                new JevResult.Answered(classification, summary), SourceResult.unavailable(NOT_GATHERED),
                SourceResult.unavailable(NOT_GATHERED), SourceResult.unavailable(NOT_GATHERED));
    }

    public static PrOutcome notAnswered(int number, List<String> checks, PullRequest pullRequest, String reason,
            String message, String jevModel, Integer submissionChars, Integer estimatedTokens, Integer limit) {
        return new PrOutcome(number, checks, pullRequest,
                new JevResult.NotAnswered(reason, message, jevModel, submissionChars, estimatedTokens, limit),
                SourceResult.unavailable(NOT_GATHERED), SourceResult.unavailable(NOT_GATHERED),
                SourceResult.unavailable(NOT_GATHERED));
    }
}
