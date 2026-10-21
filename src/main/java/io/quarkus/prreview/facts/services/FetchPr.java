package io.quarkus.prreview.facts.services;


import io.quarkus.prreview.facts.model.PullRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches PR diff + metadata via read-only {@code gh} (design §3.1 {@code fetch-pr}). {@code base.sha}
 * is captured once here and every trust fact pins to it (invariant I-3). All returned text is
 * untrusted data (invariant I-4).
 */
@ApplicationScoped
public class FetchPr {

    @Inject
    GhClient gh;

    @Inject
    ObjectMapper mapper;

    public PullRequest fetch(int number) {
        String ref = String.valueOf(number);
        String json = gh.run(gh.withRepo("pr", "view", ref, "--json",
                "number,title,body,author,baseRefName,baseRefOid,headRefOid,labels,closingIssuesReferences"));
        String diff = gh.run(gh.withRepo("pr", "diff", ref));

        try {
            JsonNode n = mapper.readTree(json);
            List<String> labels = new ArrayList<>();
            for (JsonNode l : n.path("labels")) {
                labels.add(l.path("name").asText());
            }
            StringBuilder linked = new StringBuilder();
            for (JsonNode issue : n.path("closingIssuesReferences")) {
                linked.append(issue.path("title").asText("")).append('\n')
                        .append(issue.path("body").asText("")).append('\n');
            }
            return new PullRequest(
                    n.path("number").asInt(number),
                    n.path("title").asText(""),
                    n.path("body").asText(""),
                    labels,
                    n.path("author").path("login").asText(""),
                    n.path("baseRefName").asText(""),
                    n.path("baseRefOid").asText(""),
                    n.path("headRefOid").asText(""),
                    linked.toString(),
                    diff);
        } catch (Exception e) {
            throw new GhClient.GhException("Could not parse gh pr view output: " + e.getMessage());
        }
    }
}
