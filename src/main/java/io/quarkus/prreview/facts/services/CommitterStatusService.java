package io.quarkus.prreview.facts.services;


import io.quarkus.prreview.facts.model.AuthorHistory;
import io.quarkus.prreview.facts.model.CommitterStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Resolves committer status against {@code COMMITTERS.adoc} read <em>at {@code base.sha}</em>
 * (design §3.1, invariant I-3) — never from the PR's head/diff, so a PR cannot promote its own author.
 * Any failure degrades to {@link CommitterStatus.Level#UNKNOWN} (treated as non-committer for the §4
 * Blocker, never a silent pass — design §9).
 */
@ApplicationScoped
public class CommitterStatusService {

    @Inject
    GhClient gh;

    @ConfigProperty(name = "gh.repo")
    java.util.Optional<String> repo;

    @ConfigProperty(name = "committers.file", defaultValue = "COMMITTERS.adoc")
    String committersFile;

    public CommitterStatus resolve(String authorLogin, String baseSha, AuthorHistory history) {
        String repoName = repo.orElse("");
        if (authorLogin == null || authorLogin.isBlank() || repoName.isBlank()
                || baseSha == null || baseSha.isBlank()) {
            return CommitterStatus.unknown();
        }
        try {
            String raw = gh.run("api",
                    "repos/" + repoName + "/contents/" + committersFile + "?ref=" + baseSha,
                    "--jq", ".content");
            String decoded = new String(Base64.getMimeDecoder().decode(raw.strip()), StandardCharsets.UTF_8);
            boolean listed = decoded.toLowerCase(Locale.ROOT).contains(authorLogin.toLowerCase(Locale.ROOT));
            if (listed) {
                return new CommitterStatus(CommitterStatus.Level.COMMITTER, baseSha);
            }
            boolean firstTimer = history != null && history.resolved() && history.priorMergedPrCount() == 0;
            return new CommitterStatus(
                    firstTimer ? CommitterStatus.Level.FIRST_TIMER : CommitterStatus.Level.CONTRIBUTOR, baseSha);
        } catch (RuntimeException e) {
            return CommitterStatus.unknown();
        }
    }
}
