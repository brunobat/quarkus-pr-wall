package io.quarkus.prreview.pipelines.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.services.ConfidenceGate;
import io.quarkus.prreview.interpret.model.Thresholds;
import io.quarkus.prreview.interpret.rules.services.MessageCatalog;
import io.quarkus.prreview.interpret.rules.services.RulesetLoader;
import io.quarkus.prreview.jev.services.JevService;
import io.quarkus.prreview.questions.services.QuestionRegistry;
import io.quarkus.prreview.questions.model.QuestionSet;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Shared plumbing every deep pipeline uses (plan §7): evaluate a state against a question set and band the
 * answers through the one {@link ConfidenceGate}. Centralizes the jev call + gate so each pipeline is just
 * {@code buildState} + a pure {@code interpret}.
 */
@ApplicationScoped
public class DeepSupport {

    @Inject
    JevService jev;

    @Inject
    QuestionRegistry questions;

    @Inject
    ConfidenceGate gate;

    @Inject
    RulesetLoader rulesets;

    @Inject
    ObjectMapper mapper;

    @Inject
    MessageCatalog messages;

    public ObjectMapper mapper() {
        return mapper;
    }

    public String summary(String key) {
        return messages.summary(key);
    }

    public Thresholds thresholds() {
        return rulesets.get().thresholds();
    }

    public QuestionSet questionSet(String name) {
        return questions.get(name);
    }

    /** Evaluate {@code state} against the named question set and return the banded answers. */
    public Uni<Map<String, BandedAnswer>> ask(String questionName, Object state) {
        QuestionSet qs = questions.get(questionName);
        Thresholds t = thresholds();
        return jev.evaluate(state, qs.questions()).map(resp -> gate.band(resp, t));
    }
}
