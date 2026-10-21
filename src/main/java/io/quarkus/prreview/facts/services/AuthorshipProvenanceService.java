package io.quarkus.prreview.facts.services;

import io.quarkus.prreview.facts.model.AuthorshipProvenance;
import io.quarkus.prreview.facts.model.ChangedFile;
import io.quarkus.prreview.facts.model.CommitMeta;
import io.quarkus.prreview.facts.model.FileTag;
import io.quarkus.prreview.facts.model.PullRequest;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the {@link AuthorshipProvenance} for one PR (authorship-provenance design §4.1): purely
 * deterministic, no network beyond what {@link FetchPr} already fetched, no model call.
 *
 * <p>The whole service only reports what the PR declares about itself. It does not classify an author
 * as human or AI, because that classification is not available from any signal that survives contact
 * with reality — the research behind the design rules out stylometry, signature verification and
 * date-skew heuristics by name. What is left is markers an author volunteered, and the account type
 * GitHub itself assigns. Both are recorded; neither is a verdict.
 */
@ApplicationScoped
public class AuthorshipProvenanceService {

    /** A GFM task-list item that has been ticked, at the start of a line. */
    private static final Pattern COMPLETED_CHECKLIST_ITEM =
            Pattern.compile("(?m)^\\s*[-*+]\\s+\\[[xX]]\\s");

    private List<String> aiSignals = List.of();
    private Set<String> benignBots = Set.of();

    @PostConstruct
    void loadLists() {
        aiSignals = List.copyOf(readLines("ai-provenance-signals.txt").stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).toList());
        benignBots = Set.copyOf(new LinkedHashSet<>(readLines("benign-bots.txt").stream()
                .map(AuthorshipProvenanceService::normalizeLogin).toList()));
    }

    public AuthorshipProvenance resolve(PullRequest pr, List<ChangedFile> files) {
        if (pr == null) {
            return AuthorshipProvenance.unknown();
        }
        List<CommitMeta> commits = pr.commits();
        return new AuthorshipProvenance(
                pr.authorIsBot(),
                benignBots.contains(normalizeLogin(pr.authorLogin())),
                declaresAiAssistance(pr, commits),
                hasHumanCommitAuthor(commits),
                addedLines(files, FileTag.DOCS),
                addedLines(files, FileTag.RUNTIME) + addedLines(files, FileTag.TEST),
                completedChecklistItems(pr.body()),
                commits);
    }

    /**
     * True when the author left a marker — in a commit trailer <em>or</em> in the PR body. Both halves
     * matter: on quarkusio/quarkus the body-footer population and the commit-trailer population barely
     * overlap, so checking only commits (the obvious implementation) misses most declarations.
     */
    private boolean declaresAiAssistance(PullRequest pr, List<CommitMeta> commits) {
        if (containsSignal(pr.body())) {
            return true;
        }
        for (CommitMeta c : commits) {
            if (containsSignal(c.messageBody()) || containsSignal(c.messageHeadline())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSignal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        return aiSignals.stream().anyMatch(haystack::contains);
    }

    /**
     * A commit GitHub resolved to a real user account that is not routine automation. A bot-opened PR
     * carrying such a commit is a human's work the bot merely delivered, which is why the two facts are
     * kept apart rather than collapsed into one "is a bot PR" flag.
     */
    private boolean hasHumanCommitAuthor(List<CommitMeta> commits) {
        return commits.stream().anyMatch(c -> {
            String login = normalizeLogin(c.authorLogin());
            return !login.isEmpty() && !benignBots.contains(login);
        });
    }

    private static int addedLines(List<ChangedFile> files, FileTag tag) {
        if (files == null) {
            return 0;
        }
        return files.stream().filter(f -> f.tag() == tag).mapToInt(ChangedFile::addedLineCount).sum();
    }

    static int completedChecklistItems(String body) {
        if (body == null || body.isBlank()) {
            return 0;
        }
        Matcher m = COMPLETED_CHECKLIST_ITEM.matcher(body);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    /**
     * The same account reaches us as {@code dependabot} (GraphQL), {@code dependabot[bot]} (REST) and
     * {@code app/dependabot} ({@code gh pr list}). One spelling wins so the allowlist has one entry.
     */
    static String normalizeLogin(String login) {
        if (login == null) {
            return "";
        }
        String s = login.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("app/")) {
            s = s.substring("app/".length());
        }
        if (s.endsWith("[bot]")) {
            s = s.substring(0, s.length() - "[bot]".length());
        }
        return s;
    }

    private static List<String> readLines(String resource) {
        List<String> out = new ArrayList<>();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return out;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.strip();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    out.add(s);
                }
            }
        } catch (Exception e) {
            // Fail safe: an unreadable list means no signal fires, never a signal fired by default.
            return List.of();
        }
        return out;
    }
}
