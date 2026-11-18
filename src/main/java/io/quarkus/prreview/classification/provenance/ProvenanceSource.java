package io.quarkus.prreview.classification.provenance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import io.quarkus.prreview.clients.gh.model.GhCommit;

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
        List<Person> commitAuthors = new ArrayList<>();
        for (GhCommit.Author subject : commitAuthorSubjects(authorship)) {
            commitAuthors.add(who.commitAuthor(subject));
        }
        return new SourceResult.Available<>(new Provenance(author, List.copyOf(commitAuthors), AiLabel.NONE, readAt));
    }

    /**
     * The people to label besides the PR author: each commit's git author with an account, once, in first-seen order.
     * A commit's later authors come from {@code Co-authored-by:} trailers, which the PR controls, so they are never
     * labelled even when GitHub matched them to an account. Logins compare case-insensitively, as GitHub's do.
     */
    static List<GhCommit.Author> commitAuthorSubjects(GhAuthorship authorship) {
        Map<String, GhCommit.Author> subjects = new LinkedHashMap<>();
        String prAuthor = authorship.login() == null ? null : key(authorship.login());
        for (GhCommit commit : authorship.commits()) {
            if (commit.authors().isEmpty() || commit.authors().getFirst().login() == null) {
                continue;
            }
            GhCommit.Author gitAuthor = commit.authors().getFirst();
            String key = key(gitAuthor.login());
            if (!key.equals(prAuthor)) {
                subjects.putIfAbsent(key, gitAuthor);
            }
        }
        return List.copyOf(subjects.values());
    }

    private static String key(String login) {
        return login.toLowerCase(Locale.ROOT);
    }
}
