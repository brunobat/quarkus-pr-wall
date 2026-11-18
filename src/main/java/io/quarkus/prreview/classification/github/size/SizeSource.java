package io.quarkus.prreview.classification.github.size;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.prreview.classification.github.size.model.PrSize;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhSize;

/** The size classification source: GitHub's counts for a PR, or why they are unavailable. Never throws for gh. */
@ApplicationScoped
public class SizeSource {

    private static final Logger LOG = Logger.getLogger(SizeSource.class);

    private final GhClient gh;

    public SizeSource(GhClient gh) {
        this.gh = gh;
    }

    public SourceResult<PrSize> gather(int pr) {
        try {
            GhSize size = gh.size(pr);
            return new SourceResult.Available<>(new PrSize(size.changedFiles(), size.additions(), size.deletions()));
        } catch (GhException e) {
            LOG.warnf(e, "PR #%d: size unavailable", pr);
            return SourceResult.unavailable("gh: " + e.getMessage().strip().replaceAll("\\s+", " "));
        }
    }
}
