package io.quarkus.prreview.classification.github.size;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.prreview.classification.github.GitHubFactsService;
import io.quarkus.prreview.classification.github.size.model.PrSize;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhSize;
import io.quarkus.prreview.log.FailureLog;

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
            GhSize size = facts.size(pr).orElseGet(() -> gh.size(pr));
            return new SourceResult.Available<>(new PrSize(size.changedFiles(), size.additions(), size.deletions()));
        } catch (GhException e) {
            FailureLog.warn(LOG, "PR #" + pr + ": size unavailable: gh", e);
            return SourceResult.unavailable("gh: " + e.getMessage().strip().replaceAll("\\s+", " "));
        }
    }
}
