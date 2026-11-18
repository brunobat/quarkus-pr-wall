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
import io.quarkus.prreview.clients.gh.model.GhChecks;
import io.quarkus.prreview.clients.gh.model.GhComment;
import io.quarkus.prreview.clients.gh.model.GhSize;

/**
 * GitHub's facts for every PR of the run, read in bulk once before the PRs are classified: one {@code gh pr list}
 * call per batch for the sizes, one for the checks and, for the PRs with a check that did not pass, one for the
 * comments. Each call fails on its own; what it would have returned is then simply not here. Kept for this run
 * only.
 */
@ApplicationScoped
public class GitHubFacts {

    private static final Logger LOG = Logger.getLogger(GitHubFacts.class);

    private final GhClient gh;
    private final Map<Integer, GhSize> sizes = new ConcurrentHashMap<>();
    private final Map<Integer, GhChecks> checks = new ConcurrentHashMap<>();
    private final Map<Integer, List<GhComment>> comments = new ConcurrentHashMap<>();

    public GitHubFacts(GhClient gh) {
        this.gh = gh;
    }

    /** Reads the facts of {@code prs} in bulk. Never throws for gh. */
    public void prefetch(List<Integer> prs) {
        sizes.clear();
        checks.clear();
        comments.clear();
        read("sizes", prs, gh::sizes, sizes);
        read("checks", prs, gh::checks, checks);
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
                LOG.warnf(e, "bulk %s for PRs %s failed; each is read on its own", fact, batch);
            }
        }
    }

    /** The PR's size from the bulk read, or empty when it has none. */
    public Optional<GhSize> size(int pr) {
        return Optional.ofNullable(sizes.get(pr));
    }

    /** The PR's checks from the bulk read, or empty when it has none. */
    public Optional<GhChecks> checks(int pr) {
        return Optional.ofNullable(checks.get(pr));
    }

    /** The PR's comments from the bulk read, or empty when it has none. */
    public Optional<List<GhComment>> comments(int pr) {
        return Optional.ofNullable(comments.get(pr));
    }
}
