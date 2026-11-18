package io.quarkus.prreview.classification.provenance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkus.prreview.classification.github.GitHubFacts;
import io.quarkus.prreview.classification.provenance.ai.AiRules;
import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Provenance;
import io.quarkus.prreview.classification.provenance.who.LoginMemo;
import io.quarkus.prreview.classification.provenance.who.WhoRules;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;
import io.quarkus.prreview.clients.gh.model.GhCommit;
import io.quarkus.prreview.config.ReviewConfig;

/**
 * The authorship-provenance classification source: who wrote a PR, from GitHub's facts only, or why that could not be
 * read. Who wrote the PR comes from the run's bulk read when it has it, else from its own call. A lookup that fails
 * for one person labels only that person; the source is unavailable only when the PR's
 * authorship itself could not be read. Never throws for gh.
 */
@ApplicationScoped
public class ProvenanceSource {

    private static final Logger LOG = Logger.getLogger(ProvenanceSource.class);

    private final GhClient gh;
    private final GitHubFacts facts;
    private final WhoRules who;
    private final LoginMemo memo;
    private final AiRules ai;
    private final int maxPeople;

    @Inject
    public ProvenanceSource(GhClient gh, GitHubFacts facts, WhoRules who, LoginMemo memo, AiRules ai,
            ReviewConfig config) {
        this(gh, facts, who, memo, ai, config.provenance().maxPeople());
    }

    ProvenanceSource(GhClient gh, GitHubFacts facts, WhoRules who, LoginMemo memo, AiRules ai, int maxPeople) {
        this.gh = gh;
        this.facts = facts;
        this.who = who;
        this.memo = memo;
        this.ai = ai;
        this.maxPeople = maxPeople;
    }

    public SourceResult<Provenance> gather(int pr) {
        GhAuthorship authorship;
        try {
            authorship = facts.authorship(pr).orElseGet(() -> gh.authorship(pr));
        } catch (GhException e) {
            LOG.warnf(e, "PR #%d: provenance unavailable", pr);
            return SourceResult.unavailable("gh: " + e.getMessage().strip().replaceAll("\\s+", " "));
        }
        Instant readAt = Instant.now();
        Person author = who.prAuthor(pr, authorship);
        List<Person> commitAuthors = new ArrayList<>();
        int lookups = 0;
        for (GhCommit.Author subject : commitAuthorSubjects(authorship)) {
            if (WhoRules.isBotShaped(subject)) {
                commitAuthors.add(who.commitAuthor(subject, authorship.baseSha()));
                continue;
            }
            var known = memo.get(subject.login(), authorship.baseSha());
            if (known.isPresent()) {
                commitAuthors.add(known.get());
            } else if (lookups >= maxPeople) {
                commitAuthors.add(Person.other(subject.login(), "lookup limit"));
            } else {
                lookups++;
                Person person = who.commitAuthor(subject, authorship.baseSha());
                memo.put(subject.login(), authorship.baseSha(), person);
                commitAuthors.add(person);
            }
        }
        List<Person> labelled = List.copyOf(commitAuthors);
        return new SourceResult.Available<>(new Provenance(author, labelled, ai.label(authorship, author, labelled),
                readAt));
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
