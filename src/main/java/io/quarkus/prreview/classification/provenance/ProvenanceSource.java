package io.quarkus.prreview.classification.provenance;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.prreview.classification.provenance.model.AiLabel;
import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Provenance;
import io.quarkus.prreview.classification.provenance.who.WhoRules;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;

/**
 * The authorship-provenance classification source: who wrote a PR, from GitHub's facts only, or why that could not be
 * read. A lookup that fails for one person labels only that person; the source is unavailable only when the PR's
 * authorship itself could not be read. Never throws for gh.
 */
@ApplicationScoped
public class ProvenanceSource {

    private static final Logger LOG = Logger.getLogger(ProvenanceSource.class);

    private final GhClient gh;
    private final WhoRules who;

    public ProvenanceSource(GhClient gh, WhoRules who) {
        this.gh = gh;
        this.who = who;
    }

    public SourceResult<Provenance> gather(int pr) {
        GhAuthorship authorship;
        try {
            authorship = gh.authorship(pr);
        } catch (GhException e) {
            LOG.warnf(e, "PR #%d: provenance unavailable", pr);
            return SourceResult.unavailable("gh: " + e.getMessage().strip().replaceAll("\\s+", " "));
        }
        Instant readAt = Instant.now();
        Person author = who.prAuthor(pr, authorship);
        return new SourceResult.Available<>(new Provenance(author, List.of(), AiLabel.NONE, readAt));
    }
}
