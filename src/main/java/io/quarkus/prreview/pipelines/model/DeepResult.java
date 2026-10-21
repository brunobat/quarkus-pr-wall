package io.quarkus.prreview.pipelines.model;

import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.model.Finding;

import java.util.List;
import java.util.Map;

/**
 * The output of one deep pipeline (plan §7). It carries both the structured {@link Finding}s this
 * pipeline produced <em>and</em> its banded answers, because a few cross-pipeline couplings are resolved
 * later in the app layer (plan §7): test-adequacy + exempt-class (suppression/downgrade) and inconsistency
 * corroborating breaking-change. Those combiners read the raw banded answers, never a second gate decision.
 *
 * @param concern  the concern key this pipeline owns, which is also its identity
 * @param findings structured findings (may be empty)
 * @param answers  banded answers (empty if the pipeline was skipped/errored)
 * @param status   run status for report provenance (design §7/§9)
 * @param note     short human-readable note (e.g. skip reason, error summary); may be null
 */
public record DeepResult(
        String concern,
        List<Finding> findings,
        Map<String, BandedAnswer> answers,
        DeepStatus status,
        String note) {

    public DeepResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        answers = answers == null ? Map.of() : Map.copyOf(answers);
    }

    public static DeepResult ok(String concern, List<Finding> findings, Map<String, BandedAnswer> answers) {
        return new DeepResult(concern, findings, answers, DeepStatus.OK, null);
    }

    public static DeepResult silent(String concern, Map<String, BandedAnswer> answers) {
        return new DeepResult(concern, List.of(), answers, DeepStatus.OK, null);
    }

    public static DeepResult skipped(String concern, String reason) {
        return new DeepResult(concern, List.of(), Map.of(), DeepStatus.SKIPPED, reason);
    }

    public static DeepResult errored(String concern, String error) {
        return new DeepResult(concern, List.of(), Map.of(), DeepStatus.ERRORED, error);
    }
}
