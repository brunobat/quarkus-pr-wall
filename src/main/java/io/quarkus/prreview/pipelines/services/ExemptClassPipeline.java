package io.quarkus.prreview.pipelines.services;


import io.quarkus.prreview.pipelines.model.DeepResult;
import io.quarkus.prreview.pipelines.model.TriageOutcome;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.prreview.facts.model.Facts;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The exempt-class deep pipeline — test-exempt-class judgment (plan §7 exempt-class, design §4.0). It produces
 * <em>no findings of its own</em>: it only judges which exempt class the runtime surface falls into. It meets
 * test-adequacy exactly once, in the app-layer combiner (plan §7 exempt-class / Phase 4), which reads these
 * banded answers to suppress or downgrade the test-adequacy finding. The mixed-change fail-safe stays
 * deterministic there — {@code jev} can never override the all-or-nothing rule.
 *
 * <p>Per I-4, PR text is only ever carried as delimited DATA and is never usable to decide an exemption; these
 * questions are static templates that judge the hunks alone.
 */
@ApplicationScoped
public class ExemptClassPipeline implements DeepPipeline {

    private static final String CONCERN = "exempt-class";
    private static final String QUESTIONS = "exempt-class";

    @Inject
    DeepSupport support;

    @Override
    public String concern() {
        return CONCERN;
    }

    @Override
    public Uni<DeepResult> run(Facts facts, TriageOutcome triage) {
        // No runtime surface ⇒ nothing for test-adequacy to fire on, so no exempt-class judgment is needed.
        if (!facts.hasRuntimeChange()) {
            return Uni.createFrom().item(DeepResult.skipped(CONCERN, "no runtime change"));
        }
        ObjectNode state = buildState(facts);
        return support.ask(QUESTIONS, state)
                // Never emits — it hands its banded answers to the test-adequacy combiner (plan §7 exempt-class).
                .map(answers -> DeepResult.silent(CONCERN, answers))
                .onFailure().recoverWithItem(t -> DeepResult.errored(CONCERN, String.valueOf(t.getMessage())));
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
