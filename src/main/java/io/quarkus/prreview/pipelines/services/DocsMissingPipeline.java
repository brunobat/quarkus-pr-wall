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

import java.util.List;
import java.util.Map;

/**
 * Pipeline E — docs-missing (plan §7E). Needs-attention only, never a Blocker. Fires when the diff introduces
 * new user-facing config / public API / a noteworthy feature without corresponding docs. A shaky
 * "docs present" answer is treated as missing (escalate, I-5), never as a clear.
 */
@ApplicationScoped
public class DocsMissingPipeline implements DeepPipeline {

    private static final String CONCERN = "docs-missing";
    private static final String QUESTIONS = "docs-missing";

    @Inject
    DeepSupport support;

    @Override
    public String id() {
        return "E";
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
        // All files so jev can see both the new config/API and any docs files in the same diff.
        root.set("files", StateNodes.files(support.mapper(), facts.files()));
        return root;
    }

    /** §7E interpretation. */
    List<Finding> interpret(Map<String, BandedAnswer> answers) {
        BandedAnswer newSurface = answers.get("introduces_documentable_surface");
        BandedAnswer hasDocs = answers.get("has_docs");
        if (newSurface == null) {
            return List.of();
        }
        // Nothing new introduced → silence.
        if (newSurface.isConfirmNo()) {
            return List.of();
        }
        boolean introduces = newSurface.isConfirmYes() || (newSurface.atLeastFlag() && newSurface.yes());
        if (!introduces) {
            return List.of();
        }
        // Docs confidently present → cleared.
        if (hasDocs != null && hasDocs.isConfirmYes()) {
            return List.of();
        }
        // Both at CONFIRM → firm finding; otherwise tentative (sub-CONFIRM, escalate).
        boolean firm = newSurface.isConfirmYes() && hasDocs != null && hasDocs.isConfirmNo();
        return List.of(finding(!firm));
    }

    private Finding finding(boolean tentative) {
        return Finding.of(Severity.NEEDS_ATTENTION, CONCERN, "docs-missing", support.summary("docs-missing"))
                .tentative(tentative)
                .withRationale("pipeline:E");
    }
}
