package io.quarkus.prreview.pipelines.services;


import io.quarkus.prreview.pipelines.model.DeepResult;
import io.quarkus.prreview.pipelines.model.TriageOutcome;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.prreview.facts.model.Facts;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Pipeline B — test-exempt-class judgment (plan §7B, design §4.0). B produces <em>no findings of its own</em>:
 * it only judges which exempt class the runtime surface falls into. A and B meet exactly once, in the app-layer
 * combiner (plan §7B / Phase 4), which reads B's banded answers to suppress or downgrade A's finding. The
 * mixed-change fail-safe stays deterministic there — {@code jev} can never override the all-or-nothing rule.
 *
 * <p>Per I-4, PR text is only ever carried as delimited DATA and is never usable to decide an exemption; B's
 * questions are static templates that judge the hunks alone.
 */
@ApplicationScoped
public class ExemptClassPipeline implements DeepPipeline {

    private static final String CONCERN = "exempt-class";
    private static final String QUESTIONS = "exempt-class";

    @Inject
    DeepSupport support;

    @Override
    public String id() {
        return "B";
    }

    @Override
    public String concern() {
        return CONCERN;
    }

    @Override
    public Uni<DeepResult> run(Facts facts, TriageOutcome triage) {
        // No runtime surface ⇒ nothing for A to fire on, so no exempt-class judgment is needed.
        if (!facts.hasRuntimeChange()) {
            return Uni.createFrom().item(DeepResult.skipped(id(), CONCERN, "no runtime change"));
        }
        ObjectNode state = buildState(facts);
        return support.ask(QUESTIONS, state)
                // B never emits — it hands its banded answers to the A+B combiner (plan §7B).
                .map(answers -> DeepResult.silent(id(), CONCERN, answers))
                .onFailure().recoverWithItem(t -> DeepResult.errored(id(), CONCERN, String.valueOf(t.getMessage())));
    }

    ObjectNode buildState(Facts facts) {
        ObjectNode root = support.mapper().createObjectNode();
        root.set("runtime_files", StateNodes.files(support.mapper(), facts.runtimeFiles()));
        // In-tree test PATHS near the change (fact, never contents — the agent never executes, §8).
        root.set("existing_test_paths", StateNodes.testPaths(support.mapper(), facts.testFiles()));
        // PR text present as DATA, explicitly not usable to decide exemption (I-4, §4.0).
        root.set("untrusted", StateNodes.untrusted(support.mapper(), facts.pr()));
        return root;
    }
}
