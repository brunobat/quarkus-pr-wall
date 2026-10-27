package io.quarkus.prreview.triage;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.decision.response.DecisionResponse;
import io.quarkus.arc.All;
import io.quarkus.prreview.classification.api.ClassificationCheck;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.submission.JevSubmission;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.jev.JevClient;
import io.quarkus.prreview.clients.jev.model.JevDecision;
import io.quarkus.prreview.pr.DiffFiles;
import io.quarkus.prreview.pr.model.PrContext;
import io.quarkus.prreview.pr.model.PullRequest;

/**
 * Classifies one PR: fetch it, let every check ask its questions, send them to {@code jev} in one submission and
 * keep the answers unchanged.
 */
@ApplicationScoped
public class PrClassifier {

    private final GhClient gh;
    private final JevClient jev;
    private final List<ClassificationCheck> checks;

    public PrClassifier(GhClient gh, JevClient jev, @All List<ClassificationCheck> checks) {
        this.gh = gh;
        this.jev = jev;
        this.checks = checks;
    }

    public Classification classify(int number) {
        PullRequest pr = gh.fetch(number);
        PrContext context = new PrContext(pr, DiffFiles.parse(pr.diff()));
        JevSubmission submission = JevSubmission.of(context, checks);
        JevDecision decision = jev.decide(submission.toDecisionRequest());
        DecisionResponse response = decision.response();
        return new Classification(context, submission.results(response), response.modelName(), response.tokenUsage(),
                decision.submissionChars(), decision.limit());
    }
}
