package io.quarkus.prreview.pipelines.services;


import io.quarkus.prreview.pipelines.model.DeepResult;
import io.quarkus.prreview.pipelines.model.TriageOutcome;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.prreview.facts.model.Facts;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pipeline F — domain-expert routing (plan §7F). Needs-attention only, and it never adjudicates: it only names
 * which domain experts should see the change. Several domains may fire at once, so (unlike the other pipelines)
 * F does not collapse to a single most-severe finding — each flagged domain is its own routing note. Security
 * paths are independently floored by I-1 in triage; this is the advisory routing on top.
 */
@ApplicationScoped
public class DomainRoutingPipeline implements DeepPipeline {

    private static final String CONCERN = "domain-routing";
    private static final String QUESTIONS = "domain-routing";

    /** One routing domain: its noul question id, the finding concern key, and its message. */
    private record Domain(String answerId, String concern, String messageKey) {
    }

    private static final List<Domain> DOMAINS = List.of(
            new Domain("touches_security", "domain-security", "domain-expert-security"),
            new Domain("touches_messaging", "domain-messaging", "domain-expert-messaging"),
            new Domain("touches_persistence", "domain-persistence", "domain-expert-persistence"),
            new Domain("touches_native_image", "domain-native", "domain-expert-native"));

    @Inject
    DeepSupport support;

    @Override
    public String id() {
        return "F";
    }

    @Override
    public String concern() {
        return CONCERN;
    }

    @Override
    public Uni<DeepResult> run(Facts facts, TriageOutcome triage) {
        if (!facts.hasRuntimeChange()) {
            return Uni.createFrom().item(DeepResult.skipped(id(), CONCERN, "no runtime change"));
        }
        ObjectNode state = buildState(facts);
        return support.ask(QUESTIONS, state)
                .map(answers -> DeepResult.ok(id(), CONCERN, interpret(answers), answers))
                .onFailure().recoverWithItem(t -> DeepResult.errored(id(), CONCERN, String.valueOf(t.getMessage())));
    }

    ObjectNode buildState(Facts facts) {
        ObjectNode root = support.mapper().createObjectNode();
        root.set("files", StateNodes.files(support.mapper(), facts.files()));
        return root;
    }

    /** §7F interpretation — each domain that is FLAG+ (or CONFIRM-yes) yields one routing note. */
    List<Finding> interpret(Map<String, BandedAnswer> answers) {
        List<Finding> findings = new ArrayList<>();
        for (Domain d : DOMAINS) {
            BandedAnswer a = answers.get(d.answerId());
            if (a == null) {
                continue;
            }
            boolean fires = a.isConfirmYes() || (a.atLeastFlag() && a.yes());
            if (fires) {
                findings.add(Finding.of(Severity.NEEDS_ATTENTION, d.concern(), d.messageKey(),
                                support.summary(d.messageKey()))
                        .tentative(!a.isConfirm())
                        .withRationale("pipeline:F"));
            }
        }
        return findings;
    }
}
