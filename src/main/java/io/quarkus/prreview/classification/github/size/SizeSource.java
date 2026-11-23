package io.quarkus.prreview.classification.github.size;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.prreview.classification.github.GitHubFacts;
import io.quarkus.prreview.classification.github.size.model.PrSize;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhSize;
import io.quarkus.prreview.log.FailureLog;

/** The size classification source: GitHub's counts for a PR, or why they are unavailable. Never throws for gh. */
@ApplicationScoped
public class SizeSource {

    private static final Logger LOG = Logger.getLogger(SizeSource.class);

    private final GhClient gh;
    private final GitHubFacts facts;

    public SizeSource(GhClient gh, GitHubFacts facts) {
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
