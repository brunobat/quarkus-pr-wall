package io.quarkus.prreview.facts.services;


import io.quarkus.prreview.facts.model.AuthorHistory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Author context queried via the GitHub API by login (design §3.1, invariant I-3) — never from PR
 * branch content. Floor-only (design §6): it may only raise review-priority. Any failure degrades to
 * {@link AuthorHistory#unknown()}.
 */
@ApplicationScoped
public class AuthorHistoryService {

    @Inject
    GhClient gh;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "gh.repo")
    java.util.Optional<String> repo;

    public AuthorHistory resolve(String authorLogin) {
        if (authorLogin == null || authorLogin.isBlank()) {
            return AuthorHistory.unknown();
        }
        int merged = 0;
        List<String> areas = new ArrayList<>();
        long accountAgeDays = -1;

        try {
            String repoName = repo.orElse("");
            if (!repoName.isBlank()) {
                String search = gh.run("search", "prs",
                        "--repo", repoName, "--author", authorLogin, "--state", "merged",
                        "--json", "number", "--limit", "100");
                JsonNode arr = mapper.readTree(search);
                merged = arr.isArray() ? arr.size() : 0;
            }
        } catch (Exception e) {
            return AuthorHistory.unknown();
        }

        try {
            String user = gh.run("api", "users/" + authorLogin, "--jq", ".created_at");
            String createdAt = user.strip().replace("\"", "");
            Instant created = Instant.parse(createdAt);
            accountAgeDays = Duration.between(created, Instant.now()).toDays();
        } catch (RuntimeException e) {
            accountAgeDays = -1;
        }

        return new AuthorHistory(merged, areas, accountAgeDays, true);
    }
}
