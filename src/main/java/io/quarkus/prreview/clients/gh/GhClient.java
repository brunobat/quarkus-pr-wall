package io.quarkus.prreview.clients.gh;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.prreview.classification.github.model.PrSize;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;
import io.quarkus.prreview.clients.gh.model.GhCheck;
import io.quarkus.prreview.clients.gh.model.GhCheckSuite;
import io.quarkus.prreview.clients.gh.model.GhChecks;
import io.quarkus.prreview.clients.gh.model.GhComment;
import io.quarkus.prreview.clients.gh.model.GhCommit;
import io.quarkus.prreview.config.ReviewConfig;
import io.quarkus.prreview.triage.model.PullRequest;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only access to GitHub through the {@code gh} CLI. Only {@code gh pr view}, {@code gh pr diff},
 * {@code gh pr list} and GET requests through {@code gh api} to a fixed set of paths can run; anything else is refused
 * before a process starts.
 */
@ApplicationScoped
public class GhClient {

    /** The longest search string one bulk call sends: GitHub's search refuses longer queries. */
    public static final int MAX_SEARCH_CHARS = 256;

    /** Read-only subcommands only: {@code pr list} reads facts for many PRs per request. */
    private static final Set<List<String>> ALLOWED_SUBCOMMANDS = Set.of(List.of("pr", "view"), List.of("pr", "diff"),
            List.of("pr", "list"));

    /** A GitHub login as it may appear in an {@code api} path: letters, digits and dashes, optionally {@code [bot]}. */
    static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9-]+(\\[bot\\])?");
    /** A full commit SHA. */
    static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    /** The HTTP status line {@code gh api -i} prints first, e.g. {@code HTTP/2.0 404 Not Found}. */
    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/\\S+ ([0-9]{3})\\b.*");
    /** A {@code gh api --cache} duration, as Go writes one: e.g. {@code 3600s} or {@code 1h}. */
    private static final Pattern CACHE_TTL = Pattern.compile("([0-9]+(ns|us|ms|s|m|h))+");

    /** The fields that say who wrote a PR: its author, description, base commit and commits. */
    static final String AUTHORSHIP_FIELDS = "author,body,baseRefOid,commits";

    private final ReviewConfig config;
    private final JsonMapper mapper;

    public GhClient(ReviewConfig config, JsonMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    /** Fetches the PR's metadata: number, title, URL, state and draft flag, with an empty diff. */
    public PullRequest metadata(int pr) {
        String json = run(List.of("pr", "view", Integer.toString(pr), "--json", "number,title,url,state,isDraft"));
        View view;
        try {
            view = mapper.readValue(json, View.class);
        } catch (JacksonException e) {
            throw new GhException("unexpected output from gh pr view " + pr + ": " + e.getOriginalMessage(), e);
        }
        return new PullRequest(view.number(), view.title(), view.url(), view.state(), Boolean.TRUE.equals(view.isDraft()),
                "");
    }

    /**
     * {@code metadata} with the PR's unified diff. When the diff fails (e.g. GitHub's 406 for more than 300 files),
     * the exception carries the metadata, so the error report can still show the title.
     */
    public PullRequest withDiff(PullRequest metadata) {
        String diff;
        try {
            diff = run(List.of("pr", "diff", Integer.toString(metadata.number())));
        } catch (GhException e) {
            throw new GhException(e.getMessage(), metadata, e);
        }
        return new PullRequest(metadata.number(), metadata.title(), metadata.url(), metadata.state(), metadata.draft(),
                diff);
    }

    private record View(int number, String title, String url, String state, @JsonProperty("isDraft") Boolean isDraft) {
    }

    /**
     * Fetches the PR's size counts on their own, so they are known even when the diff is refused (GitHub's 406 for
     * more than 300 files) and a failure here loses nothing else. A missing or null count is a failure, never 0.
     */
    public PrSize size(int pr) {
        String json = run(List.of("pr", "view", Integer.toString(pr), "--json", "additions,deletions,changedFiles"));
        SizeView view;
        try {
            view = mapper.readValue(json, SizeView.class);
        } catch (JacksonException e) {
            throw new GhException("unexpected size output from gh pr view " + pr + ": " + e.getOriginalMessage(), e);
        }
        if (view.additions() == null || view.deletions() == null || view.changedFiles() == null) {
            throw new GhException("gh pr view " + pr + " did not report additions, deletions and changedFiles");
        }
        return new PrSize(view.changedFiles(), view.additions(), view.deletions());
    }

    private record SizeView(Integer additions, Integer deletions, Integer changedFiles) {
    }

    /**
     * Fetches the PR's head commit and its check rollup on their own call. No checks is an empty list; a missing or
     * null rollup is a failure, so "none" and "could not be read" stay apart.
     */
    public GhChecks checks(int pr) {
        String json = run(List.of("pr", "view", Integer.toString(pr), "--json", "headRefOid,statusCheckRollup"));
        GhChecks checks;
        try {
            checks = mapper.readValue(json, GhChecks.class);
        } catch (JacksonException e) {
            throw new GhException("unexpected checks output from gh pr view " + pr + ": " + e.getOriginalMessage(), e);
        }
        if (checks.rollup() == null) {
            throw new GhException("gh pr view " + pr + " did not report statusCheckRollup");
        }
        return checks;
    }

    /** Fetches the PR's conversation comments on their own call, in the order gh returns them (oldest first). */
    public List<GhComment> comments(int pr) {
        String json = run(List.of("pr", "view", Integer.toString(pr), "--json", "comments"));
        CommentsView view;
        try {
            view = mapper.readValue(json, CommentsView.class);
        } catch (JacksonException e) {
            throw new GhException("unexpected comments output from gh pr view " + pr + ": " + e.getOriginalMessage(),
                    e);
        }
        if (view.comments() == null) {
            throw new GhException("gh pr view " + pr + " did not report comments");
        }
        return toComments(view.comments());
    }

    private static List<GhComment> toComments(List<CommentView> comments) {
        return comments.stream().filter(c -> c != null).map(c -> new GhComment(c.author() == null ? null
                : c.author().login(), c.body() == null ? "" : c.body(), c.createdAt())).toList();
    }

    private record CommentsView(List<CommentView> comments) {
    }

    private record CommentView(Author author, String body, String createdAt) {
    }

    private record Author(String login) {
    }

    /**
     * Fetches who wrote the PR on its own call: its author, description, base commit and commits. A missing author is
     * kept as {@code null} (a deleted account); missing commits or base are a failure.
     */
    public GhAuthorship authorship(int pr) {
        String json = run(List.of("pr", "view", Integer.toString(pr), "--json", AUTHORSHIP_FIELDS));
        AuthorshipView view;
        try {
            view = mapper.readValue(json, AuthorshipView.class);
        } catch (JacksonException e) {
            throw new GhException("unexpected authorship output from gh pr view " + pr + ": " + e.getOriginalMessage(),
                    e);
        }
        GhAuthorship authorship = toAuthorship(view);
        if (authorship == null) {
            throw new GhException("gh pr view " + pr + " did not report baseRefOid and commits");
        }
        return authorship;
    }

    /** The view as our model, or {@code null} when it lacks the base commit or the commits. */
    private static GhAuthorship toAuthorship(AuthorshipView view) {
        if (view == null || view.baseRefOid() == null || view.commits() == null) {
            return null;
        }
        List<GhCommit> commits = view.commits().stream().filter(c -> c != null)
                .map(c -> new GhCommit(c.oid() == null ? "" : c.oid(), orEmpty(c.messageHeadline()),
                        orEmpty(c.messageBody()), c.authors() == null ? List.of()
                                : c.authors().stream().filter(a -> a != null)
                                        .map(a -> new GhCommit.Author(blankToNull(a.login()), a.name())).toList()))
                .toList();
        AuthorView author = view.author();
        return new GhAuthorship(author == null ? null : blankToNull(author.login()), author == null ? null : author.name(),
                author != null && Boolean.TRUE.equals(author.isBot()), orEmpty(view.body()), view.baseRefOid(),
                commits);
    }

    /** gh writes an account it could not match as an empty login. */
    private static String blankToNull(String login) {
        return login == null || login.isBlank() ? null : login;
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    private record AuthorshipView(AuthorView author, String body, String baseRefOid, List<CommitView> commits) {
    }

    private record AuthorView(String login, String name, @JsonProperty("is_bot") Boolean isBot) {
    }

    private record CommitView(String oid, String messageHeadline, String messageBody, List<CommitAuthorView> authors) {
    }

    private record CommitAuthorView(String login, String name) {
    }

    /**
     * The PR author's association with the repository as GitHub reports it now, e.g. {@code MEMBER} or
     * {@code FIRST_TIME_CONTRIBUTOR}. Never cached: it changes as PRs are merged.
     */
    public String association(int pr) {
        String value = run(List.of("api", "repos/" + apiRepo() + "/pulls/" + pr, "--jq",
                ".author_association")).strip();
        if (value.isEmpty() || value.equals("null")) {
            throw new GhException("GitHub did not report the author association of PR " + pr);
        }
        return value;
    }

    /**
     * Whether GitHub answers that the configured repository has no pull request with this number: HTTP 404 on its
     * REST path, read from the status line rather than from {@code gh}'s message. Any other status, or no status at
     * all (e.g. a network failure), is {@code false}: the number may still be a PR. Never cached.
     */
    public boolean isNotAPullRequest(int pr) {
        Result result;
        try {
            result = execute(List.of("api", "repos/" + apiRepo() + "/pulls/" + pr, "-i"));
        } catch (GhException e) {
            return false;
        }
        var status = STATUS_LINE.matcher(result.stdout().lines().findFirst().orElse("").strip());
        return status.matches() && Integer.parseInt(status.group(1)) == 404;
    }

    /**
     * Whether {@code login} is a member of the configured repository's org, as GitHub shows it to this token: a
     * 2xx answer is a member, a 404 is not, and any other status is a failure. A private member looks like a
     * non-member to a token that does not belong to the org.
     */
    public boolean isMember(String login) {
        String path = "orgs/" + owner() + "/members/" + validLogin(login);
        Result result = execute(withCache(List.of("api", path, "-i")));
        String first = result.stdout().lines().findFirst().orElse("").strip();
        var status = STATUS_LINE.matcher(first);
        if (!status.matches()) {
            String message = result.stderr().strip();
            throw new GhException(message.isEmpty() ? "gh api " + path + " gave no HTTP status" : message);
        }
        int code = Integer.parseInt(status.group(1));
        if (code == 404) {
            return false;
        }
        if (code / 100 != 2) {
            throw new GhException("HTTP " + code);
        }
        return true;
    }

    /** Whether {@code login} authored a commit reachable from {@code baseSha} in the configured repository. */
    public boolean hasCommitsBefore(String login, String baseSha) {
        if (baseSha == null || !SHA.matcher(baseSha).matches()) {
            throw new GhException("not a commit SHA: " + baseSha);
        }
        String json = run(withCache(List.of("api", "repos/" + apiRepo() + "/commits?author=" + validLogin(login)
                + "&sha=" + baseSha + "&per_page=1")));
        Object[] commits;
        try {
            commits = mapper.readValue(json, Object[].class);
        } catch (JacksonException e) {
            throw new GhException("unexpected output from gh api commits: " + e.getOriginalMessage(), e);
        }
        if (commits == null) {
            throw new GhException("gh api commits returned no list");
        }
        return commits.length > 0;
    }

    /**
     * The check suites on commit {@code sha}, as GitHub lists them (at most 100). A SHA that is not 40 hex characters
     * is refused before any process starts; a missing list is a failure, so "none" and "could not be read" stay apart.
     * Never cached: a suite's state changes as workflows are approved and run.
     */
    public List<GhCheckSuite> checkSuites(String sha) {
        if (sha == null || !SHA.matcher(sha).matches()) {
            throw new GhException("not a commit SHA: " + sha);
        }
        String json = run(List.of("api", "repos/" + apiRepo() + "/commits/" + sha + "/check-suites?per_page=100"));
        CheckSuitesView view;
        try {
            view = mapper.readValue(json, CheckSuitesView.class);
        } catch (JacksonException e) {
            throw new GhException("unexpected output from gh api check-suites: " + e.getOriginalMessage(), e);
        }
        if (view == null || view.checkSuites() == null) {
            throw new GhException("gh api check-suites returned no check_suites list");
        }
        return view.checkSuites();
    }

    private record CheckSuitesView(@JsonProperty("check_suites") List<GhCheckSuite> checkSuites) {
    }

    /**
     * {@code args} with {@code --cache <GH_CACHE_TTL>} in whole seconds, as {@code gh api --cache} reads it. A TTL
     * under one second, zero or negative included, means no caching, so the flag is left out.
     */
    private List<String> withCache(List<String> args) {
        long seconds = config.gh().cacheTtl().toSeconds();
        if (seconds < 1) {
            return args;
        }
        List<String> cached = new ArrayList<>(args);
        cached.add("--cache");
        cached.add(seconds + "s");
        return cached;
    }

    /** A login that is safe to put in an {@code api} path; anything else is a lookup failure, not a crash. */
    private static String validLogin(String login) {
        if (login == null || !LOGIN.matcher(login).matches()) {
            throw new GhException("not a GitHub login: " + login);
        }
        return login;
    }

    /**
     * The size counts of each requested PR found by one {@code gh pr list} search, keyed by number. A PR the search
     * did not return, or returned without its number or every count, is absent; the search may also match PRs that weren't
     * requested (it matches text), and those are dropped.
     */
    public Map<Integer, PrSize> sizes(List<Integer> prs) {
        Map<Integer, PrSize> sizes = new LinkedHashMap<>();
        for (SizeItem item : list(prs, "additions,deletions,changedFiles", SizeItem[].class)) {
            if (item.number() != null && item.additions() != null && item.deletions() != null && item.changedFiles() != null) {
                sizes.put(item.number(), new PrSize(item.changedFiles(), item.additions(), item.deletions()));
            }
        }
        sizes.keySet().retainAll(prs);
        return sizes;
    }

    private record SizeItem(Integer number, Integer additions, Integer deletions, Integer changedFiles) {
    }

    /**
     * The head commit and check rollup of each requested PR found by one {@code gh pr list} search, keyed by number;
     * absent and extra PRs as for {@link #sizes(List)}. gh cuts the rollup at 100 entries here.
     */
    public Map<Integer, GhChecks> checks(List<Integer> prs) {
        Map<Integer, GhChecks> checks = new LinkedHashMap<>();
        for (ChecksItem item : list(prs, "headRefOid,statusCheckRollup", ChecksItem[].class)) {
            if (item.number() != null && item.statusCheckRollup() != null) {
                checks.put(item.number(), new GhChecks(item.headRefOid(), item.statusCheckRollup()));
            }
        }
        checks.keySet().retainAll(prs);
        return checks;
    }

    private record ChecksItem(Integer number, String headRefOid, List<GhCheck> statusCheckRollup) {
    }

    /**
     * The comments of each requested PR found by one {@code gh pr list} search, keyed by number; absent and extra PRs
     * as for {@link #sizes(List)}. gh cuts each list at 100 comments here.
     */
    public Map<Integer, List<GhComment>> comments(List<Integer> prs) {
        Map<Integer, List<GhComment>> comments = new LinkedHashMap<>();
        for (CommentsItem item : list(prs, "comments", CommentsItem[].class)) {
            if (item.number() != null && item.comments() != null) {
                comments.put(item.number(), toComments(item.comments()));
            }
        }
        comments.keySet().retainAll(prs);
        return comments;
    }

    private record CommentsItem(Integer number, List<CommentView> comments) {
    }

    /**
     * Who wrote each requested PR found by one {@code gh pr list} search, keyed by number; absent and extra PRs as for
     * {@link #sizes(List)}, and a PR without its base or commits is absent too. gh cuts each commit list at 100 here.
     */
    public Map<Integer, GhAuthorship> authorships(List<Integer> prs) {
        Map<Integer, GhAuthorship> authorships = new LinkedHashMap<>();
        for (AuthorshipItem item : list(prs, AUTHORSHIP_FIELDS, AuthorshipItem[].class)) {
            GhAuthorship authorship = toAuthorship(
                    new AuthorshipView(item.author(), item.body(), item.baseRefOid(), item.commits()));
            if (item.number() != null && authorship != null) {
                authorships.put(item.number(), authorship);
            }
        }
        authorships.keySet().retainAll(prs);
        return authorships;
    }

    private record AuthorshipItem(Integer number, AuthorView author, String body, String baseRefOid,
            List<CommitView> commits) {
    }

    /** {@code prs} split, in order, into lists whose numbers joined with spaces fit {@link #MAX_SEARCH_CHARS}. */
    public static List<List<Integer>> batches(List<Integer> prs) {
        List<List<Integer>> batches = new ArrayList<>();
        List<Integer> batch = new ArrayList<>();
        int chars = 0;
        for (int pr : prs) {
            int length = Integer.toString(pr).length();
            if (!batch.isEmpty() && chars + 1 + length > MAX_SEARCH_CHARS) {
                batches.add(List.copyOf(batch));
                batch.clear();
                chars = 0;
            }
            chars += (batch.isEmpty() ? 0 : 1) + length;
            batch.add(pr);
        }
        if (!batch.isEmpty()) {
            batches.add(List.copyOf(batch));
        }
        return batches;
    }

    /** The command line of one bulk call; {@code prs} must fit one batch. */
    static List<String> listArgs(List<Integer> prs, String fields) {
        String search = String.join(" ", prs.stream().map(String::valueOf).toList());
        if (prs.isEmpty() || search.length() > MAX_SEARCH_CHARS) {
            throw new IllegalArgumentException("not one batch of PRs: " + prs);
        }
        return List.of("pr", "list", "--state", "all", "--search", search, "--json", "number," + fields, "--limit",
                Integer.toString(prs.size()));
    }

    private <T> List<T> list(List<Integer> prs, String fields, Class<T[]> type) {
        String json = run(listArgs(prs, fields));
        T[] items;
        try {
            items = mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new GhException("unexpected output from gh pr list: " + e.getOriginalMessage(), e);
        }
        if (items == null) {
            throw new GhException("gh pr list returned no list");
        }
        return Arrays.stream(items).filter(i -> i != null).toList();
    }

    /** Builds the full command line; refuses anything but the allowed read-only subcommands. */
    List<String> command(List<String> args) {
        if (!args.isEmpty() && "api".equals(args.getFirst())) {
            return apiCommand(args);
        }
        if (args.size() < 2 || !ALLOWED_SUBCOMMANDS.contains(args.subList(0, 2))) {
            throw new IllegalArgumentException("gh subcommand not allowed: " + args);
        }
        List<String> command = new ArrayList<>();
        command.add(config.gh().binary());
        command.addAll(args);
        command.add("-R");
        command.add(config.gh().repo());
        return command;
    }

    /**
     * A GET request through {@code gh api}. {@code gh api} has no {@code -R}: the path names the configured repository
     * or its owner itself, and must be one of the pull request, commits-by-author, commit check-suites or
     * org-membership paths. The only flags are {@code -i}, {@code --cache <duration>} and {@code --jq <expr>}, so
     * nothing can change the method or send a body ({@code -X}, {@code -f}, {@code -F}, {@code --input}), and
     * {@code graphql} and {@code actions/...} are never valid paths.
     */
    private List<String> apiCommand(List<String> args) {
        if (args.size() < 2 || !allowedApiPath(args.get(1))) {
            throw new IllegalArgumentException("gh api path not allowed: " + args);
        }
        for (int i = 2; i < args.size(); i++) {
            String flag = args.get(i);
            if (flag.equals("-i")) {
                continue;
            }
            if ((flag.equals("--cache") || flag.equals("--jq")) && i + 1 < args.size()) {
                String value = args.get(++i);
                if (flag.equals("--cache") ? CACHE_TTL.matcher(value).matches() : !value.startsWith("-")) {
                    continue;
                }
            }
            throw new IllegalArgumentException("gh api flag not allowed: " + args);
        }
        List<String> command = new ArrayList<>();
        command.add(config.gh().binary());
        command.addAll(args);
        return command;
    }

    private boolean allowedApiPath(String path) {
        String repo = Pattern.quote(apiRepo());
        String owner = Pattern.quote(owner());
        String login = LOGIN.pattern();
        return path.matches("repos/" + repo + "/pulls/[1-9][0-9]*")
                || path.matches(
                        "repos/" + repo + "/commits\\?author=" + login + "&sha=" + SHA.pattern() + "&per_page=1")
                || path.matches("repos/" + repo + "/commits/" + SHA.pattern() + "/check-suites\\?per_page=100")
                || path.matches("orgs/" + owner + "/members/" + login);
    }

    /**
     * The {@code OWNER/REPO} that {@code gh api} paths name: {@code GH_REPO}, or its last two parts when it is
     * {@code github.com/OWNER/REPO}, a form {@code -R} also accepts. Any other shape is a lookup failure, since
     * {@code gh api} would ask the wrong path or the wrong host.
     */
    String apiRepo() {
        String repo = config.gh().repo();
        String[] parts = repo.split("/", -1);
        if (parts.length == 3 && parts[0].equalsIgnoreCase("github.com")) {
            parts = new String[] { parts[1], parts[2] };
        }
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new GhException("GH_REPO is not OWNER/REPO or github.com/OWNER/REPO: " + repo);
        }
        return parts[0] + "/" + parts[1];
    }

    /** The owner part of {@link #apiRepo()}: the org whose membership is looked up. */
    String owner() {
        String repo = apiRepo();
        return repo.substring(0, repo.indexOf('/'));
    }

    /**
     * Kills gh and every process it started. A child still holding gh's stdout or stderr would otherwise keep the
     * pipes open, and the readers blocked, after gh itself is gone. Children are listed first: once gh dies they are
     * no longer its descendants.
     */
    private static void kill(Process process) {
        List<ProcessHandle> children = process.descendants().toList();
        process.destroyForcibly();
        children.forEach(ProcessHandle::destroyForcibly);
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(read(stream));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /** A reader task's result; a failure to read gh's output is a gh failure like any other. */
    private static String output(CompletableFuture<String> stream) {
        try {
            return stream.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new GhException("cannot read gh output: " + cause.getMessage(), cause);
        }
    }

    private String run(List<String> args) {
        Result result = execute(args);
        if (result.exitCode() != 0) {
            String message = result.stderr().strip();
            throw new GhException(message.isEmpty() ? "gh exited with " + result.exitCode() : message);
        }
        return result.stdout();
    }

    /** What one gh invocation that ran to completion printed, and its exit code. */
    private record Result(int exitCode, String stdout, String stderr) {
    }

    /** Runs gh to completion; a gh that cannot start, times out or is interrupted is a {@link GhException}. */
    private Result execute(List<String> args) {
        List<String> command = command(args);
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new GhException("cannot run " + command.getFirst() + ": " + e.getMessage(), e);
        }
        // Read both streams concurrently, each on its own virtual thread, so a large diff can't block the process on
        // a full pipe and the readers never queue behind other work on a shared pool.
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        try {
            if (!process.waitFor(config.gh().timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new GhException("gh " + String.join(" ", args) + " timed out after " + config.gh().timeout());
            }
            return new Result(process.exitValue(), output(stdout), output(stderr));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(process);
            throw new GhException("interrupted while running gh", e);
        }
    }

    private static String read(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
