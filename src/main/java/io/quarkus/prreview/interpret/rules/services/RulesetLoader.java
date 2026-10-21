package io.quarkus.prreview.interpret.rules.services;



import io.quarkus.prreview.interpret.rules.model.Ruleset;
import io.quarkus.prreview.interpret.rules.model.Rule;
import io.quarkus.prreview.interpret.rules.model.TuningConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.prreview.interpret.model.Thresholds;
import io.quarkus.prreview.questions.model.QuestionSet;
import io.quarkus.prreview.questions.services.QuestionRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads and validates the tuning surface (plan §5.6): the named dials in {@code thresholds.yaml} plus the
 * {@code rules} block of the concern module that owns them. Rules live in the same file as the questions
 * they read ({@code questions/triage.json}) because a rule and the answer id it references are one
 * reviewable unit — rename an id and every rule reading it breaks in the same diff, which is also what
 * lets {@link RulesetLint} check a rule against the exact ids its own file declares.
 *
 * <p>Only triage has rules: {@link RuleEngine} runs at triage, and the deep pipelines interpret their
 * answers in Java. The file layout now says so, instead of implying a per-concern ruleset that is never
 * executed.
 *
 * <p>{@code valueRef}s are resolved to named thresholds at load time (so numbers stay referenced-by-name,
 * never inlined) and the ruleset is run through {@link RulesetLint} — a lint failure throws here, so an
 * unsafe or malformed ruleset never reaches the engine. Rules are parsed with a strict mapper: an unknown
 * key is a typo and fails the load rather than being silently dropped.
 */
@ApplicationScoped
public class RulesetLoader {

    /** The only concern module whose rules are executed — {@link RuleEngine} runs at triage. */
    private static final String RULED_MODULE = "triage";
    private static final String THRESHOLDS = "thresholds.yaml";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    private final ObjectMapper strict = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
            .findAndRegisterModules();

    @Inject
    QuestionRegistry questions;

    private volatile TuningConfig cached;

    public TuningConfig get() {
        TuningConfig local = cached;
        if (local == null) {
            synchronized (this) {
                local = cached;
                if (local == null) {
                    local = load();
                    cached = local;
                }
            }
        }
        return local;
    }

    private TuningConfig load() {
        Thresholds thresholds = readYaml(THRESHOLDS, Thresholds.class);
        QuestionSet module = questions.get(RULED_MODULE);

        List<Rule> rules = new ArrayList<>();
        for (Rule raw : parseRules(module)) {
            rules.add(resolveValue(raw, thresholds));
        }
        Ruleset ruleset = new Ruleset(module.rulesVersion(), rules);

        List<String> errors = RulesetLint.validate(ruleset, thresholds, knownAnswerIds(module));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Ruleset lint failed — unsafe or malformed ruleset refused:\n - "
                    + String.join("\n - ", errors));
        }
        return new TuningConfig(ruleset, thresholds);
    }

    /** Parse the module's {@code rules} array; an unknown or misspelled key fails here, loudly. */
    private List<Rule> parseRules(QuestionSet module) {
        JsonNode node = module.rules();
        if (node == null || !node.isArray()) {
            throw new IllegalStateException(
                    "questions/" + module.name() + ".json must contain a 'rules' array");
        }
        try {
            return List.of(strict.treeToValue(node, Rule[].class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Malformed rule in questions/" + module.name() + ".json: " + e.getOriginalMessage(), e);
        }
    }

    /** Resolve {@code valueRef} against the named thresholds; leaves value null if unknown (lint reports). */
    private static Rule resolveValue(Rule rule, Thresholds thresholds) {
        if (rule.value() == null && rule.valueRef() != null && thresholds.named().containsKey(rule.valueRef())) {
            return rule.withValue(thresholds.named().get(rule.valueRef()));
        }
        return rule;
    }

    /** The answer ids these rules may legally reference: the ids declared alongside them, and only those. */
    private static Set<String> knownAnswerIds(QuestionSet module) {
        Set<String> ids = new LinkedHashSet<>();
        Iterator<String> it = module.questions().fieldNames();
        while (it.hasNext()) {
            ids.add(it.next());
        }
        return ids;
    }

    private <T> T readYaml(String resource, Class<T> type) {
        try (InputStream in = classpath(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing tuning resource: " + resource);
            }
            return yaml.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse " + resource, e);
        }
    }

    private static InputStream classpath(String resource) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
    }

    // Exposed for completeness / possible diagnostics.
    JsonNode rawThresholds() {
        return readYaml(THRESHOLDS, JsonNode.class);
    }
}
