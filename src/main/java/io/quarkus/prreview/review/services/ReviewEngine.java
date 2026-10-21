package io.quarkus.prreview.review.services;


import io.quarkus.prreview.review.model.AuthorContext;
import io.quarkus.prreview.review.model.PipelineRun;
import io.quarkus.prreview.review.model.Provenance;
import io.quarkus.prreview.review.model.ReviewResult;
import io.quarkus.prreview.facts.model.Facts;
import io.quarkus.prreview.facts.services.FactsService;
import io.quarkus.prreview.pipelines.services.DeepPipeline;
import io.quarkus.prreview.pipelines.model.DeepResult;
import io.quarkus.prreview.pipelines.model.TriageOutcome;
import io.quarkus.prreview.pipelines.services.TriagePipeline;
import io.quarkus.prreview.report.services.OpusProseClient;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single-PR review orchestrator (plan §10): {@code facts → triage → parallel deep fan-out → combine}.
 *
 * <ul>
 *   <li><b>Full depth</b> (a single explicitly-targeted PR) runs every deep pipeline regardless of the
 *       triage gate (design §3.3).</li>
 *   <li><b>Range depth</b> prunes the deep pipelines to the triage escalation set (plus exempt-class
 *       whenever test-adequacy runs, since their coupling needs the exempt-class judgment).</li>
 * </ul>
 *
 * Pipelines fan out via Mutiny and never throw — a failed pipeline resolves to an {@code ERRORED} result and
 * the others proceed (design §9). The facts gather is the one blocking step; if it fails the PR becomes an
 * {@link ReviewResult#errored errored} result rather than taking the whole run down (design §9, §7).
 */
@ApplicationScoped
public class ReviewEngine {

    /** Every deep pipeline, named by the concern it owns; also the report's pipeline-run order. */
    private static final List<String> ALL_PIPELINES = List.of(
            "test-adequacy", "exempt-class", "breaking-change", "test-weakening",
            "docs-missing", "domain-routing", "inconsistency");

    private static final String TEST_ADEQUACY = "test-adequacy";
    private static final String EXEMPT_CLASS = "exempt-class";

    @Inject
    FactsService factsService;

    @Inject
    TriagePipeline triagePipeline;

    @Inject
    FindingCombiner combiner;

    @Inject
    OpusProseClient opus;

    @Inject
    Instance<DeepPipeline> pipelineBeans;

    private Map<String, DeepPipeline> pipelines;

    @PostConstruct
    void indexPipelines() {
        Map<String, DeepPipeline> byConcern = new LinkedHashMap<>();
        for (DeepPipeline p : pipelineBeans) {
            byConcern.put(p.concern(), p);
        }
        pipelines = byConcern;
    }

    /** Full-depth review of a single targeted PR — runs every deep pipeline. */
    public Uni<ReviewResult> review(int prNumber) {
        return review(prNumber, true);
    }

    /** Review a PR; {@code fullDepth=false} prunes deep pipelines to the triage escalation set (range mode). */
    public Uni<ReviewResult> review(int prNumber, boolean fullDepth) {
        Facts facts;
        try {
            facts = factsService.gather(prNumber);
        } catch (RuntimeException e) {
            return Uni.createFrom().item(ReviewResult.errored(prNumber, "facts: " + message(e)));
        }
        final Facts f = facts;
        return triagePipeline.triage(f)
                .flatMap(triage -> {
                    List<String> toRun = fullDepth ? ALL_PIPELINES : pipelinesForRange(triage);
                    return runDeep(f, triage, toRun).map(deep -> assemble(f, triage, deep));
                })
                .onFailure().recoverWithItem(t -> ReviewResult.errored(prNumber, "review: " + message(t)));
    }

    /**
     * Range mode: the triage escalation set, plus exempt-class whenever test-adequacy runs (their
     * coupling in {@link FindingCombiner} needs the exempt-class judgment to be present).
     */
    private static List<String> pipelinesForRange(TriageOutcome triage) {
        Set<String> escalations = triage.decisions().escalations();
        List<String> toRun = new ArrayList<>();
        for (String concern : ALL_PIPELINES) {
            if (escalations.contains(concern)
                    || (concern.equals(EXEMPT_CLASS) && escalations.contains(TEST_ADEQUACY))) {
                toRun.add(concern);
            }
        }
        return toRun;
    }

    /** Fan out the selected deep pipelines in parallel; collect their results by id. */
    private Uni<Map<String, DeepResult>> runDeep(Facts facts, TriageOutcome triage, List<String> toRun) {
        List<Uni<DeepResult>> unis = new ArrayList<>();
        for (String id : toRun) {
            DeepPipeline p = pipelines.get(id);
            if (p != null) {
                unis.add(p.run(facts, triage));
            }
        }
        if (unis.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        return Uni.combine().all().unis(unis).with(results -> {
            Map<String, DeepResult> byConcern = new LinkedHashMap<>();
            for (Object o : results) {
                DeepResult r = (DeepResult) o;
                byConcern.put(r.concern(), r);
            }
            return byConcern;
        });
    }

    private ReviewResult assemble(Facts facts, TriageOutcome triage, Map<String, DeepResult> deep) {
        List<io.quarkus.prreview.interpret.model.Finding> findings = combiner.combine(triage, deep, facts);
        List<PipelineRun> runs = new ArrayList<>();
        for (String concern : ALL_PIPELINES) {
            DeepResult r = deep.get(concern);
            if (r != null) {
                runs.add(new PipelineRun(r.concern(), r.status(), r.note()));
            }
        }
        Provenance provenance = new Provenance(
                facts.pr().headSha(),
                triage.raw() == null ? null : triage.raw().model(),
                opus.modelVersion(),
                triage.questionSetVersion(),
                triage.tuning().ruleset().version(),
                triage.tuning().thresholds().version());

        // Reporting only. Built here, read by the renderer, and by nothing in between.
        AuthorContext authorContext = new AuthorContext(
                facts.pr().authorLogin(),
                facts.committerStatus().isCommitter(),
                facts.provenance().authorIsBot(),
                facts.provenance().authorIsAllowlistedBot(),
                facts.provenance().declaresAiAssistance());

        return new ReviewResult(
                facts.pr().number(),
                facts.pr().title(),
                triage.decisions().priority(),
                triage.effectiveBlastRadius(),
                findings,
                runs,
                provenance,
                authorContext,
                ReviewResult.Status.REVIEWED,
                null);
    }

    private static String message(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
