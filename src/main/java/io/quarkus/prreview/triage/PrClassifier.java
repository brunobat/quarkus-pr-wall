package io.quarkus.prreview.triage;

import java.util.Comparator;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import dev.langchain4j.model.decision.response.DecisionResponse;
import io.quarkus.arc.All;
import io.quarkus.prreview.classification.api.ClassificationCheck;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.submission.JevSubmission;
import io.quarkus.prreview.classification.submission.ProjectContextLoader;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.jev.JevClient;
import io.quarkus.prreview.clients.jev.JevException;
import io.quarkus.prreview.clients.jev.SubmissionTooLargeException;
import io.quarkus.prreview.clients.jev.model.JevDecision;
import io.quarkus.prreview.pr.DiffFiles;
import io.quarkus.prreview.pr.model.PrContext;
import io.quarkus.prreview.pr.model.PullRequest;
import io.quarkus.prreview.report.summary.SummaryService;
import io.quarkus.prreview.triage.model.PrOutcome;

/**
 * Classifies one PR: fetch it, let every check ask its questions, send them to {@code jev} in one submission, keep
 * the answers unchanged and describe them. A failure for this PR becomes a {@link PrOutcome.NotClassified} with a
 * one-line reason (spec "Per-PR failures and exit codes"); it is never thrown, so the run goes on.
 */
@ApplicationScoped
public class PrClassifier {

    private static final Logger LOG = Logger.getLogger(PrClassifier.class);

    private final GhClient gh;
    private final JevClient jev;
    private final SummaryService summaries;
    private final List<ClassificationCheck> checks;
    private final ProjectContextLoader projectContext;

    public PrClassifier(GhClient gh, JevClient jev, SummaryService summaries, @All List<ClassificationCheck> checks,
            ProjectContextLoader projectContext) {
        this.gh = gh;
        this.jev = jev;
        this.summaries = summaries;
        this.projectContext = projectContext;
        // Sorted by id so contribution order (questions, output, fallback template) is stable across runs.
        this.checks = checks.stream().sorted(Comparator.comparing(ClassificationCheck::id)).toList();
    }

    public PrOutcome classify(int number) {
        PullRequest pr;
        try {
            pr = gh.fetch(number);
        } catch (GhException e) {
            LOG.warnf(e, "PR #%d: gh failed", number);
            // The PR's title is kept when only its diff failed.
            return new PrOutcome.NotClassified(number, checkIds(), e.pullRequest(), "gh: " + oneLine(e.getMessage()),
                    e.getMessage(), null, null, null);
        }
        if (pr.diff().isBlank()) {
            LOG.warnf("PR #%d: gh returned an empty diff", number);
            return new PrOutcome.NotClassified(number, checkIds(), pr, "empty diff", "gh pr diff returned no changes",
                    null, null, null);
        }

        PrContext context = new PrContext(pr, DiffFiles.parse(pr.diff()));
        JevSubmission submission = JevSubmission.of(context, projectContext.get(), checks);
        JevDecision decision;
        try {
            decision = jev.decide(submission.toDecisionRequest());
        } catch (SubmissionTooLargeException e) {
            LOG.warnf("PR #%d: %s", number, e.getMessage());
            return new PrOutcome.NotClassified(number, checkIds(), pr,
                    "submission %d chars exceeds limit %d".formatted(e.submissionChars(), e.limit()), e.getMessage(),
                    jev.modelName(), e.submissionChars(), e.limit());
        } catch (JevException e) {
            LOG.warnf(e, "PR #%d: jev failed", number);
            return jevFailure(number, pr, e.getMessage(), jev.modelName(), e.submissionChars(), e.limit());
        }

        DecisionResponse response = decision.response();
        Classification classification;
        try {
            classification = new Classification(context, submission.results(response), response.modelName(),
                    response.tokenUsage(), decision.submissionChars(), decision.limit(),
                    submission.projectContext());
        } catch (RuntimeException e) {
            // An answer jev's response lacks, or one of the wrong kind.
            LOG.warnf(e, "PR #%d: jev's response does not answer every question", number);
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            return jevFailure(number, pr, message, response.modelName() == null ? jev.modelName()
                    : response.modelName(), decision.submissionChars(), decision.limit());
        }
        return new PrOutcome.Classified(classification, summaries.summarize(classification));
    }

    private List<String> checkIds() {
        return checks.stream().map(ClassificationCheck::id).toList();
    }

    private PrOutcome.NotClassified jevFailure(int number, PullRequest pr, String message, String model,
            int submissionChars, int limit) {
        return new PrOutcome.NotClassified(number, checkIds(), pr, "jev: " + oneLine(message), message, model,
                submissionChars, limit);
    }

    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }
}
