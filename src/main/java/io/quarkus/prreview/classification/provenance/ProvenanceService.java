package io.quarkus.prreview.classification.provenance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkus.prreview.classification.github.GitHubFactsService;
import io.quarkus.prreview.classification.provenance.ai.AiService;
import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Provenance;
import io.quarkus.prreview.classification.provenance.who.LoginMemoService;
import io.quarkus.prreview.classification.provenance.who.WhoService;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;
import io.quarkus.prreview.clients.gh.model.GhCommit;
import io.quarkus.prreview.config.ReviewConfig;
import io.quarkus.prreview.log.FailureLog;
import io.quarkus.prreview.report.PlainText;

/**
 * The authorship-provenance classification source: who wrote a PR, from GitHub's facts only, or why that could not be
 * read. Who wrote the PR comes from the run's bulk read when it has it, else from its own call. A lookup that fails
 * for one person labels only that person; the source is unavailable only when the PR's
 * authorship itself could not be read. Never throws for gh.
 */
@ApplicationScoped
public class ProvenanceService {

    private static final Logger LOG = Logger.getLogger(ProvenanceService.class);

    private final GhClient gh;
    private final GitHubFactsService facts;
    private final WhoService who;
    private final LoginMemoService memo;
    private final AiService ai;
    private final int maxPeople;

    @Inject
    public ProvenanceService(GhClient gh, GitHubFactsService facts, WhoService who, LoginMemoService memo, AiService ai,
            ReviewConfig config) {
        this(gh, facts, who, memo, ai, config.provenance().maxPeople());
    }

    ProvenanceService(GhClient gh, GitHubFactsService facts, WhoService who, LoginMemoService memo, AiService ai, int maxPeople) {
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
            FailureLog.warn(LOG, "PR #" + pr + ": provenance unavailable: gh", e);
            return SourceResult.unavailable("gh: " + PlainText.oneLine(e.getMessage()));
        }
        Instant readAt = Instant.now();
        Person author = who.prAuthor(pr, authorship);
        List<Person> commitAuthors = new ArrayList<>();
        int lookups = 0;
        for (GhCommit.Author subject : commitAuthorSubjects(authorship)) {
            if (WhoService.isBotShaped(subject)) {
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
                Person person = label(subject, authorship.baseSha());
                memo.put(subject.login(), authorship.baseSha(), person);
                commitAuthors.add(person);
            }
        }
        List<Person> labelled = List.copyOf(commitAuthors);
        return new SourceResult.Available<>(new Provenance(author, labelled, ai.label(authorship, author, labelled),
                readAt));
    }

    /**
     * A commit author who is not bot-shaped: membership first, unless this run already found them not to be a member,
     * then their commit history before {@code baseSha}. A "not a member" answer is kept as soon as it comes, so a
     * failed history lookup is retried later without asking for membership again.
     */
    private Person label(GhCommit.Author subject, String baseSha) {
        if (!memo.knownNonMember(subject.login())) {
            var member = who.byMembership(subject);
            if (member.isPresent()) {
                return member.get();
            }
            memo.markNonMember(subject.login());
        }
        return who.nonMemberCommitAuthor(subject, baseSha);
    }

    /**
     * The people to label besides the PR author: each commit's git author with an account, once, in first-seen order.
     * A commit's later authors come from {@code Co-authored-by:} trailers, which the PR controls, so they are never
     * labelled even when GitHub matched them to an account. Logins compare as accounts: case-insensitively, as
     * GitHub's do, and with a bot's {@code app/x} and {@code x[bot]} as the same account.
     */
    static List<GhCommit.Author> commitAuthorSubjects(GhAuthorship authorship) {
        Map<String, GhCommit.Author> subjects = new LinkedHashMap<>();
        String prAuthor = authorship.login() == null ? null : WhoService.account(authorship.login());
        for (GhCommit commit : authorship.commits()) {
            if (commit.authors().isEmpty() || commit.authors().getFirst().login() == null) {
                continue;
            }
            GhCommit.Author gitAuthor = commit.authors().getFirst();
            String key = WhoService.account(gitAuthor.login());
            if (!key.equals(prAuthor)) {
                subjects.putIfAbsent(key, gitAuthor);
            }
        }
        return List.copyOf(subjects.values());
    }
}
