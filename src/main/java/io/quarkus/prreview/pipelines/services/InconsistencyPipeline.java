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
 * Pipeline G — inconsistency analysis, PR text vs. diff (plan §7G). The question is a fixed comparison template
 * over PR text carried as explicitly-delimited untrusted DATA — the structural injection defense (I-4). G is
 * independent of C: a {@code false-not-breaking-claim} is reported here as an inconsistency and separately
 * corroborates C in the app layer, but G never feeds C and C never feeds G.
 */
@ApplicationScoped
public class InconsistencyPipeline implements DeepPipeline {

    private static final String CONCERN = "inconsistency";
    private static final String QUESTIONS = "inconsistency";

    @Inject
    DeepSupport support;

    @Override
    public String id() {
        return "G";
    }

    @Override
    public String concern() {
        return CONCERN;
    }

    @Override
    public Uni<DeepResult> run(Facts facts, TriageOutcome triage) {
        // No PR text ⇒ nothing to compare against the diff.
        if (!facts.pr().hasText()) {
            return Uni.createFrom().item(DeepResult.skipped(id(), CONCERN, "no PR text"));
        }
        ObjectNode state = buildState(facts);
        return support.ask(QUESTIONS, state)
                .map(answers -> DeepResult.ok(id(), CONCERN, interpret(answers), answers))
                .onFailure().recoverWithItem(t -> DeepResult.errored(id(), CONCERN, String.valueOf(t.getMessage())));
    }

    ObjectNode buildState(Facts facts) {
        ObjectNode root = support.mapper().createObjectNode();
        // PR title/body wrapped as explicitly-delimited untrusted DATA (I-4), compared against the diff.
        root.set("untrusted", StateNodes.untrusted(support.mapper(), facts.pr()));
        root.set("files", StateNodes.files(support.mapper(), facts.files()));
        return root;
    }

    /** §7G interpretation — emit the inconsistency line only when the text leans inaccurate. */
    List<Finding> interpret(Map<String, BandedAnswer> answers) {
        BandedAnswer textMatches = answers.get("text_matches_diff");
        BandedAnswer mismatchKind = answers.get("mismatch_kind");
        if (textMatches == null || textMatches.isConfirmYes()) {
            // Accurate description (or no answer) → silence.
            return List.of();
        }
        // text_matches_diff leans "not accurate": CONFIRM-false (firm) or FLAG/HUMAN leaning no (tentative).
        boolean leansInaccurate = textMatches.isConfirmNo() || (textMatches.atLeastFlag() && !textMatches.yes());
        if (!leansInaccurate) {
            return List.of();
        }
        boolean undisclosed = mismatchKind != null && mismatchKind.isChoice("undisclosed-change");
        String messageKey = undisclosed ? "inconsistency-undisclosed" : "inconsistency";
        return List.of(Finding.of(Severity.NEEDS_ATTENTION, CONCERN, messageKey, support.summary(messageKey))
                .tentative(!textMatches.isConfirm())
                .withRationale("pipeline:G"));
    }
}
