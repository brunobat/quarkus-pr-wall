package io.quarkus.prreview.questions.services;


import io.quarkus.prreview.questions.model.QuestionSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches the externalized concern modules from {@code questions/<name>.json} on the classpath
 * (plan §3 package layout, §5.6). A concern is a self-contained module — dropping a new
 * {@code questions/<concern>.json} makes it discoverable here by name, no code change (plan §5.6).
 *
 * <p>Resource shape; {@code rulesVersion} and {@code rules} are optional and present only for triage:
 * <pre>{@code
 * {
 *   "version": "...",
 *   "rulesVersion": "...",
 *   "questions": { "<id>": { "type": "...", ... } },
 *   "rules": [ { "id": "...", "when": [...], "then": "..." } ]
 * }
 * }</pre>
 */
@ApplicationScoped
public class QuestionRegistry {

    private final Map<String, QuestionSet> cache = new ConcurrentHashMap<>();

    @Inject
    ObjectMapper mapper;

    /** Load the concern module (e.g. {@code "triage"}, {@code "test-adequacy"}). */
    public QuestionSet get(String name) {
        return cache.computeIfAbsent(name, this::load);
    }

    private QuestionSet load(String name) {
        String resource = "questions/" + name + ".json";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing question resource: " + resource);
            }
            JsonNode root = mapper.readTree(in);
            JsonNode questions = root.get("questions");
            if (questions == null || !questions.isObject()) {
                throw new IllegalStateException(resource + " must contain a 'questions' object");
            }
            String version = root.path("version").asText(name + "-unversioned");
            JsonNode rules = root.get("rules");
            String rulesVersion = rules == null ? null
                    : root.path("rulesVersion").asText(name + "-rules-unversioned");
            return new QuestionSet(name, version, questions, rulesVersion, rules);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + resource, e);
        }
    }
}
