package io.quarkus.prreview.classification.github;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.prreview.classification.github.model.PrSize;
import io.quarkus.prreview.classification.model.SourceResult;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.util.FailureLog;

/** The size classification source: GitHub's counts for a PR, or why they are unavailable. Never throws for gh. */
@ApplicationScoped
public class SizeService {

    private static final Logger LOG = Logger.getLogger(SizeService.class);

    private final GhClient gh;
    private final GitHubFactsService facts;

    public SizeService(GhClient gh, GitHubFactsService facts) {
        this.gh = gh;
        this.facts = facts;
    }

    public SourceResult<PrSize> gather(int pr) {
        try {
            return new SourceResult.Available<>(facts.size(pr).orElseGet(() -> gh.size(pr)));
        } catch (GhException e) {
            FailureLog.warn(LOG, "PR #" + pr + ": size unavailable: gh", e);
            return SourceResult.unavailable();
        }
    }
}
