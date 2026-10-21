package io.quarkus.prreview.facts.services;


import io.quarkus.prreview.facts.model.CommitMeta;
import io.quarkus.prreview.facts.model.PullRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches PR diff + metadata via read-only {@code gh} (design §3.1 {@code fetch-pr}). {@code base.sha}
 * is captured once here and every trust fact pins to it (invariant I-3). All returned text is
 * untrusted data (invariant I-4).
 *
 * <p>Metadata comes from one aliased {@code gh api graphql} document rather than per-PR
 * {@code gh pr view --json} (authorship-provenance design §4.1). Two reasons: {@code pr view} cannot
 * return {@code commit.committer} or {@code parents.totalCount} at all, and a whole sweep costs a
 * handful of rate-limit points instead of one REST call per PR. Diffs still cost one
 * {@code gh pr diff} each — GraphQL does not serve them.
 *
 * <p>{@link #prefetch} is an optimisation and never a correctness dependency: if the bulk query fails
 * for any reason the cache stays empty and each {@link #fetch} issues its own one-alias query, which is
 * the same document with a single entry.
 */
@ApplicationScoped
public class FetchPr {

    private static final Logger LOG = Logger.getLogger(FetchPr.class);

    /** Commits per PR. Beyond this the branch is a rebase candidate anyway and the tail adds nothing. */
    private static final int MAX_COMMITS = 50;

    /**
     * PRs per GraphQL document. Keeps the node count (chunk × {@link #MAX_COMMITS}) far below GitHub's
     * limit while still collapsing a 100-PR sweep into four calls.
     */
    private static final int CHUNK = 25;

    private static final String PR_FIELDS = """
            fragment prFields on PullRequest {
              number title body baseRefName headRefName baseRefOid headRefOid
              author { __typename login }
              labels(first: 50) { nodes { name } }
              closingIssuesReferences(first: 20) { nodes { title body } }
              commits(first: %d) { nodes { commit {
                oid messageHeadline messageBody authoredDate committedDate
                parents { totalCount }
                author { name user { login } }
                committer { name user { login } }
              } } }
            }""".formatted(MAX_COMMITS);

    /**
     * Metadata from the last {@link #prefetch}, consumed by {@link #fetch}. A sweep is sequential
     * (see {@code RangeEngine}), so this is written once per sweep and drained entry by entry; the
     * concurrent map is for safety, not for a concurrency the engine actually exercises.
     */
    private final Map<Integer, PullRequest> prefetched = new ConcurrentHashMap<>();

    @Inject
    GhClient gh;

    @Inject
    ObjectMapper mapper;

    /**
     * Load metadata for a whole sweep in as few calls as possible. Best-effort: a failure here is logged
     * and left to {@link #fetch} to redo one PR at a time.
     */
    public void prefetch(List<Integer> numbers) {
        prefetched.clear();
        if (numbers == null || numbers.isEmpty()) {
            return;
        }
        try {
            prefetched.putAll(fetchMetadata(numbers));
        } catch (RuntimeException e) {
            LOG.debugf("Bulk PR metadata fetch failed, falling back to one query per PR: %s", e.getMessage());
        }
    }

    public PullRequest fetch(int number) {
        PullRequest metadata = prefetched.remove(number);
        if (metadata == null) {
            metadata = fetchMetadata(List.of(number)).get(number);
        }
        if (metadata == null) {
            throw new GhClient.GhException("PR #" + number + " is not readable (deleted, or no access)");
        }
        return metadata.withDiff(gh.run(gh.withRepo("pr", "diff", String.valueOf(number))));
    }

    /** Diff-less {@link PullRequest}s keyed by number. A PR absent from the result is one GitHub did not return. */
    private Map<Integer, PullRequest> fetchMetadata(List<Integer> numbers) {
        String[] ownerName = gh.requireRepo().split("/", 2);
        Map<Integer, PullRequest> out = new LinkedHashMap<>();
        for (int from = 0; from < numbers.size(); from += CHUNK) {
            List<Integer> chunk = numbers.subList(from, Math.min(from + CHUNK, numbers.size()));
            String json = gh.run("api", "graphql", "-f", "query=" + query(chunk, ownerName[0], ownerName[1]));
            JsonNode root = parse(json);
            for (int number : chunk) {
                // A null alias plus an errors[] entry means "this PR is gone", not "the sweep failed".
                JsonNode pr = root.path("data").path("pr" + number).path("pullRequest");
                if (pr.isMissingNode() || pr.isNull()) {
                    LOG.debugf("PR #%d returned no data: %s", number, root.path("errors"));
                    continue;
                }
                out.put(number, toPullRequest(number, pr));
            }
        }
        return out;
    }

    private static String query(List<Integer> numbers, String owner, String name) {
        StringBuilder q = new StringBuilder("query {\n");
        for (int number : numbers) {
            q.append("  pr").append(number)
                    .append(": repository(owner: \"").append(owner).append("\", name: \"").append(name)
                    .append("\") { pullRequest(number: ").append(number).append(") { ...prFields } }\n");
        }
        return q.append("}\n").append(PR_FIELDS).toString();
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new GhClient.GhException("Could not parse gh api graphql output: " + e.getMessage());
        }
    }

    private static PullRequest toPullRequest(int number, JsonNode n) {
        List<String> labels = new ArrayList<>();
        for (JsonNode label : n.path("labels").path("nodes")) {
            labels.add(label.path("name").asText(""));
        }
        StringBuilder linked = new StringBuilder();
        for (JsonNode issue : n.path("closingIssuesReferences").path("nodes")) {
            linked.append(issue.path("title").asText("")).append('\n')
                    .append(issue.path("body").asText("")).append('\n');
        }
        List<CommitMeta> commits = new ArrayList<>();
        for (JsonNode node : n.path("commits").path("nodes")) {
            JsonNode c = node.path("commit");
            commits.add(new CommitMeta(
                    c.path("oid").asText(""),
                    c.path("messageHeadline").asText(""),
                    c.path("messageBody").asText(""),
                    c.path("authoredDate").asText(""),
                    c.path("committedDate").asText(""),
                    c.path("parents").path("totalCount").asInt(0),
                    c.path("author").path("name").asText(""),
                    c.path("author").path("user").path("login").asText(""),
                    c.path("committer").path("name").asText(""),
                    c.path("committer").path("user").path("login").asText("")));
        }
        return new PullRequest(
                n.path("number").asInt(number),
                n.path("title").asText(""),
                n.path("body").asText(""),
                labels,
                n.path("author").path("login").asText(""),
                "Bot".equals(n.path("author").path("__typename").asText("")),
                n.path("baseRefName").asText(""),
                n.path("headRefName").asText(""),
                n.path("baseRefOid").asText(""),
                n.path("headRefOid").asText(""),
                linked.toString(),
                commits,
                "");
    }
}
