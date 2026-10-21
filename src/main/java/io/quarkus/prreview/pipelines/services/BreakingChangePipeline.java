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
 * The breaking-change deep pipeline — break + migration/backport confirmation pass (plan §7 breaking-change).
 * Escalation-only: it fires on the diff alone, confirms the break, pins the kind, and checks the migration
 * note. The backport test is a deterministic fact ({@code is_backport}); the PR-text "not breaking" claim is
 * never judged here — the inconsistency pipeline surfaces that independently, and the two never feed each
 * other in either direction.
 */
@ApplicationScoped
public class BreakingChangePipeline implements DeepPipeline {

    private static final String CONCERN = "breaking-change";
    private static final String QUESTIONS = "breaking-change";

    @Inject
    DeepSupport support;

    @Override
    public String concern() {
        return CONCERN;
    }

    @Override
    public Uni<DeepResult> run(Facts facts, TriageOutcome triage) {
        if (!facts.hasRuntimeChange()) {
            return Uni.createFrom().item(DeepResult.skipped(CONCERN, "no runtime change"));
        }
        ObjectNode state = buildState(facts, triage);
        return support.ask(QUESTIONS, state)
                .map(answers -> DeepResult.ok(CONCERN, interpret(answers, facts), answers))
                .onFailure().recoverWithItem(t -> DeepResult.errored(CONCERN, String.valueOf(t.getMessage())));
    }

    ObjectNode buildState(Facts facts, TriageOutcome triage) {
        ObjectNode root = support.mapper().createObjectNode();
        root.set("runtime_files", StateNodes.files(support.mapper(), facts.runtimeFiles()));
        root.put("base_branch", facts.pr().baseBranch());
        // Triage's break_kind/breaks_callers as a neutral prior to confirm or overturn (plan §7 breaking-change STATE).
        ObjectNode prior = root.putObject("triage_prior");
        putPrior(prior, "break_kind", triage);
        putPrior(prior, "breaks_callers", triage);
        // PR text as DATA; questions already instruct ignoring breaking/not-breaking claims (I-4, §8).
        root.set("untrusted", StateNodes.untrusted(support.mapper(), facts.pr()));
        return root;
    }

    private static void putPrior(ObjectNode prior, String id, TriageOutcome triage) {
        BandedAnswer a = triage.answers().get(id);
        if (a == null) {
            return;
        }
        ObjectNode n = prior.putObject(id);
        n.put("band", a.band().name());
        if (a.choice() != null) {
            n.put("choice", a.choice());
        }
        if (!Double.isNaN(a.score())) {
            n.put("score", a.scoreInt());
        }
    }

    /** §7 breaking-change interpretation — fires on the diff alone; {@code is_backport} is the deterministic fact. */
    List<Finding> interpret(Map<String, BandedAnswer> answers, Facts facts) {
        BandedAnswer breaksCallers = answers.get("diff_breaks_callers");
        BandedAnswer hasMigrationNote = answers.get("diff_has_migration_note");
        if (breaksCallers == null) {
            return List.of();
        }

        if (breaksCallers.isConfirmYes()) {
            if (facts.isBackport()) {
                return List.of(finding(Severity.BLOCKER, "breaking-change-backported", false));
            }
            if (hasMigrationNote != null && hasMigrationNote.isConfirmNo()) {
                return List.of(finding(Severity.BLOCKER, "breaking-change-no-migration", false));
            }
            // Documented break on main — FYI/silence; stay silent by default (I-6).
            return List.of();
        }
        if (breaksCallers.atLeastFlag() && !breaksCallers.yes()) {
            // Leaning breaking but sub-CONFIRM → confirm + document.
            return List.of(finding(Severity.NEEDS_ATTENTION, "breaking-change-possible", true));
        }
        // Confidently not breaking, or leaning not-breaking → silence.
        return List.of();
    }

    private Finding finding(Severity severity, String messageKey, boolean tentative) {
        return Finding.of(severity, CONCERN, messageKey, support.summary(messageKey))
                .tentative(tentative)
                .withRationale("pipeline:breaking-change");
    }
}
