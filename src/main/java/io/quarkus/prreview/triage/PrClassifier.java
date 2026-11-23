package io.quarkus.prreview.triage;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import dev.langchain4j.model.decision.response.DecisionResponse;
import io.quarkus.arc.All;
import io.quarkus.prreview.classification.api.ClassificationCheck;
import io.quarkus.prreview.classification.github.ci.CiSource;
import io.quarkus.prreview.classification.github.size.SizeSource;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.provenance.ProvenanceSource;
import io.quarkus.prreview.classification.provenance.model.Provenance;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.classification.submission.JevSubmission;
import io.quarkus.prreview.classification.submission.ProjectContextLoader;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.jev.JevClient;
import io.quarkus.prreview.clients.jev.JevException;
import io.quarkus.prreview.clients.jev.SubmissionTooLargeException;
import io.quarkus.prreview.clients.jev.model.JevDecision;
import io.quarkus.prreview.config.ReviewConfig;
import io.quarkus.prreview.log.FailureLog;
import io.quarkus.prreview.pr.DiffFiles;
import io.quarkus.prreview.pr.model.PrContext;
import io.quarkus.prreview.pr.model.PullRequest;
import io.quarkus.prreview.report.PlainText;
import io.quarkus.prreview.report.summary.SummaryService;
import io.quarkus.prreview.triage.model.PrOutcome;

/**
 * Classifies one PR: read its metadata, fetch its diff, let every check ask its questions, send them to {@code jev}
 * in one submission, keep the answers unchanged and describe them, then gather GitHub's facts. A failure for this PR
 * becomes an outcome whose {@code jev} result is not answered, with a one-line reason; it is never thrown, so the run
 * goes on. The one exception is a number GitHub says is not a pull request: nothing more is looked up for it, and
 * {@link NotAPullRequestException} is thrown.
 */
@ApplicationScoped
public class PrClassifier {

    private static final Logger LOG = Logger.getLogger(PrClassifier.class);

    private final GhClient gh;
    private final JevClient jev;
    private final SummaryService summaries;
    private final List<ClassificationCheck> checks;
    private final ProjectContextLoader projectContext;
    private final SizeSource size;
    private final CiSource ci;
    private final ProvenanceSource provenance;

    public PrClassifier(GhClient gh, JevClient jev, SummaryService summaries, @All List<ClassificationCheck> checks,
            ProjectContextLoader projectContext, SizeSource size, CiSource ci, ProvenanceSource provenance) {
        this.gh = gh;
        this.size = size;
        this.ci = ci;
        this.provenance = provenance;
        this.jev = jev;
        this.summaries = summaries;
        this.projectContext = projectContext;
        // Sorted by id so contribution order (questions, output, fallback template) is stable across runs.
        this.checks = checks.stream().sorted(Comparator.comparing(ClassificationCheck::id)).toList();
    }

    /**
     * {@code jev}'s result, then each GitHub source's, whatever {@code jev}'s result was. The metadata is read first,
     * so a number that is not a pull request costs no other lookup. Who wrote the PR is then looked up on its own
     * virtual thread while {@code jev} is asked, so the PR waits for the slower of the two, not both.
     *
     * @throws NotAPullRequestException when GitHub answers that the repository has no pull request with this number
     */
    public PrOutcome classify(int number) {
        PullRequest metadata;
        GhException metadataFailure = null;
        try {
            metadata = gh.metadata(number);
        } catch (GhException e) {
            if (gh.isNotAPullRequest(number)) {
                LOG.debugf(e, "PR #%d: not a pull request: gh: %s", number, PlainText.oneLine(e.getMessage()));
                throw new NotAPullRequestException(number, e);
            }
            metadata = null;
            metadataFailure = e;
        }
        FutureTask<SourceResult<Provenance>> whoWrote = new FutureTask<>(() -> provenance.gather(number));
        Thread.ofVirtual().name("provenance-" + number).start(whoWrote);
        try {
            PrOutcome jevOnly = metadataFailure == null ? jev(number, metadata) : notFetched(number, metadataFailure);
            return new PrOutcome(number, checkIds(), jevOnly.pullRequest(), jevOnly.jev(), size.gather(number),
                    ci.gather(number), join(number, whoWrote));
        } finally {
            // Only reached unfinished when something above threw: stop the lookups rather than leave them running.
            whoWrote.cancel(true);
        }
    }

    /**
     * The provenance task's result. The source never throws for gh, and each of its gh calls has its own timeout, so
     * the wait is bounded; anything unexpected becomes an unavailable result instead of failing the PR.
     */
    private static SourceResult<Provenance> join(int number, FutureTask<SourceResult<Provenance>> task) {
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return SourceResult.unavailable("interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            LOG.warnf(cause, "PR #%d: provenance failed", number);
            String message = cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
            return SourceResult.unavailable(PlainText.oneLine(message));
        }
    }

    private PrOutcome jev(int number, PullRequest metadata) {
        PullRequest pr;
        try {
            pr = gh.withDiff(metadata);
        } catch (GhException e) {
            // The PR's title is kept when only its diff failed.
            return notFetched(number, e);
        }
        if (pr.diff().isBlank()) {
            LOG.warnf("PR #%d: gh returned an empty diff", number);
            return PrOutcome.notAnswered(number, checkIds(), pr, "empty diff", "gh pr diff returned no changes",
                    null, null, null, null);
        }

        PrContext context = new PrContext(pr, DiffFiles.parse(pr.diff()));
        JevSubmission submission = JevSubmission.of(context, projectContext.get(), checks);
        JevDecision decision;
        try {
            decision = jev.decide(submission.toDecisionRequest());
        } catch (SubmissionTooLargeException e) {
            LOG.warnf("PR #%d: %s", number, e.getMessage());
            return PrOutcome.notAnswered(number, checkIds(), pr,
                    "submission estimated at %d tokens + %d for the answer exceeds limit %d".formatted(
                            e.estimatedTokens(), ReviewConfig.Jev.ANSWER_TOKENS, e.limit()),
                    e.getMessage(), jev.modelName(), e.submissionChars(), e.estimatedTokens(), e.limit());
        } catch (JevException e) {
            FailureLog.warn(LOG, "PR #" + number + ": jev failed", e);
            return jevFailure(number, pr, e.getMessage(), jev.modelName(), e.submissionChars(), e.estimatedTokens(),
                    e.limit());
        }

        DecisionResponse response = decision.response();
        Classification classification;
        try {
            classification = new Classification(context, submission.results(response), response.modelName(),
                    response.tokenUsage(), decision.submissionChars(), decision.estimatedTokens(), decision.limit(),
                    submission.projectContext());
        } catch (RuntimeException e) {
            // An answer jev's response lacks, or one of the wrong kind.
            FailureLog.warn(LOG, "PR #" + number + ": jev's response does not answer every question", e);
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            return jevFailure(number, pr, message, response.modelName() == null ? jev.modelName()
                    : response.modelName(), decision.submissionChars(), decision.estimatedTokens(), decision.limit());
        }
        return PrOutcome.answered(classification, summaries.summarize(classification));
    }

    private PrOutcome notFetched(int number, GhException e) {
        FailureLog.warn(LOG, "PR #" + number + ": not classified: gh", e);
        return PrOutcome.notAnswered(number, checkIds(), e.pullRequest(), "gh: " + oneLine(e.getMessage()),
                e.getMessage(), null, null, null, null);
    }

    /** The ids of {@code jev}'s checks, in the order their questions are asked. */
    public List<String> checkIds() {
        return checks.stream().map(ClassificationCheck::id).toList();
    }

    private PrOutcome jevFailure(int number, PullRequest pr, String message, String model,
            int submissionChars, int estimatedTokens, int limit) {
        return PrOutcome.notAnswered(number, checkIds(), pr, "jev: " + oneLine(message), message, model,
                submissionChars, estimatedTokens, limit);
    }

    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }
}
