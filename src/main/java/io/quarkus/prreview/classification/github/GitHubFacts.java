package io.quarkus.prreview.classification.github;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.prreview.classification.github.ci.CiSource;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;
import io.quarkus.prreview.clients.gh.model.GhChecks;
import io.quarkus.prreview.clients.gh.model.GhComment;
import io.quarkus.prreview.clients.gh.model.GhSize;
import io.quarkus.prreview.log.FailureLog;

/**
 * GitHub's facts for every PR of the run, read in bulk once before the PRs are classified: one {@code gh pr list}
 * call per batch for the sizes, one for the checks, one for who wrote each PR and, for the PRs with a check that did
 * not pass, one for the comments. Each call fails on its own; what it would have returned is then simply not here. Kept for this run
 * only.
 * <p>
 * A fact is not here, so its source reads it with its own per-PR call, when the PR was missing from the results,
 * when the bulk call failed, or when the list may have been cut off: gh returns at most 100 checks, comments or
 * commits per PR in a bulk call, so exactly 100 is not trusted.
 */
@ApplicationScoped
public class GitHubFacts {

    private static final Logger LOG = Logger.getLogger(GitHubFacts.class);

    /** The most checks or comments gh returns for one PR in a bulk call. */
    static final int BULK_LIST_CAP = 100;

    private final GhClient gh;
    private final Map<Integer, GhSize> sizes = new ConcurrentHashMap<>();
    private final Map<Integer, GhChecks> checks = new ConcurrentHashMap<>();
    private final Map<Integer, List<GhComment>> comments = new ConcurrentHashMap<>();
    private final Map<Integer, GhAuthorship> authorships = new ConcurrentHashMap<>();

    public GitHubFacts(GhClient gh) {
        this.gh = gh;
    }

    /** Reads the facts of {@code prs} in bulk. Never throws for gh. */
    public void prefetch(List<Integer> prs) {
        sizes.clear();
        checks.clear();
        comments.clear();
        authorships.clear();
        read("sizes", prs, gh::sizes, sizes);
        read("checks", prs, gh::checks, checks);
        read("authorships", prs, gh::authorships, authorships);
        List<Integer> failing = prs.stream().filter(checks::containsKey)
                .filter(pr -> CiSource.anyNotPassed(checks.get(pr))).toList();
        read("comments", failing, gh::comments, comments);
    }

    private static <T> void read(String fact, List<Integer> prs, Function<List<Integer>, Map<Integer, T>> call,
            Map<Integer, T> into) {
        for (List<Integer> batch : GhClient.batches(prs)) {
            try {
                into.putAll(call.apply(batch));
            } catch (GhException e) {
                FailureLog.warn(LOG, "bulk %s for PRs %s failed; each is read on its own: gh".formatted(fact, batch), e);
            }
        }
    }

    /** The PR's size from the bulk read, or empty when it has none. */
    public Optional<GhSize> size(int pr) {
        return Optional.ofNullable(sizes.get(pr));
    }

    /** The PR's checks from the bulk read, or empty when it has none or the rollup may have been cut off. */
    public Optional<GhChecks> checks(int pr) {
        return Optional.ofNullable(checks.get(pr)).filter(c -> c.rollup().size() != BULK_LIST_CAP);
    }

    /** Who wrote the PR, from the bulk read, or empty when it has none or its commits may have been cut off. */
    public Optional<GhAuthorship> authorship(int pr) {
        return Optional.ofNullable(authorships.get(pr)).filter(a -> a.commits().size() != BULK_LIST_CAP);
    }

    /** The PR's comments from the bulk read, or empty when it has none or the list may have been cut off. */
    public Optional<List<GhComment>> comments(int pr) {
        return Optional.ofNullable(comments.get(pr)).filter(c -> c.size() != BULK_LIST_CAP);
    }
}
