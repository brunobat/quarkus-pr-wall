package io.quarkus.prreview.review.services;

import io.quarkus.prreview.facts.model.Facts;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.interpret.rules.services.MessageCatalog;
import io.quarkus.prreview.pipelines.model.DeepResult;
import io.quarkus.prreview.pipelines.model.TriageOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges triage findings and the independent deep-pipeline findings into one final, deduplicated finding list
 * (plan §7, Phase 4). This is the <em>only</em> place the few deliberate cross-pipeline couplings are resolved,
 * and they are resolved here in code — never inside a {@code jev} call:
 *
 * <ul>
 *   <li><b>test-adequacy + exempt-class</b> — the exempt-class judgment may suppress or downgrade the
 *       test-adequacy finding, but only behind the deterministic mixed-change fail-safe (design §4.0): if any
 *       runtime hunk is deterministically not-exempt, no exemption applies whatever exempt-class says.</li>
 *   <li><b>inconsistency corroborates breaking-change</b> — a {@code false-not-breaking-claim} strengthens the
 *       breaking-change rationale; it never creates or escalates that finding (plan §7 inconsistency).</li>
 * </ul>
 *
 * Everything is pure over the pipeline outputs + pinned facts, so it is unit-tested with hand-authored results.
 */
@ApplicationScoped
public class FindingCombiner {

    private static final String TEST_ADEQUACY = "test-adequacy";
    private static final String EXEMPT_CLASS = "exempt-class";
    private static final String BREAKING_CHANGE = "breaking-change";
    private static final String INCONSISTENCY = "inconsistency";

    @Inject
    MessageCatalog messages;

    /** Produce the final finding list for a PR from triage + the deep results that ran. */
    public List<Finding> combine(TriageOutcome triage, Map<String, DeepResult> deep, Facts facts) {
        List<Finding> all = new ArrayList<>(triage.decisions().findings());

        // The one place test-adequacy and exempt-class meet.
        all.addAll(applyExemptClass(deep.get(TEST_ADEQUACY), deep.get(EXEMPT_CLASS), facts));

        // Breaking-change, strengthened by inconsistency's corroboration but never created by it.
        all.addAll(corroborateBreakingChange(deep.get(BREAKING_CHANGE), deep.get(INCONSISTENCY)));

        // Remaining pipelines contribute their findings directly.
        addFindings(all, deep.get("test-weakening"));
        addFindings(all, deep.get("docs-missing"));
        addFindings(all, deep.get("domain-routing"));
        addFindings(all, deep.get(INCONSISTENCY));

        return mostSeverePerConcern(all);
    }

    // --- test-adequacy + exempt-class ----------------------------------------------------------------

    List<Finding> applyExemptClass(DeepResult testAdequacy, DeepResult exempt, Facts facts) {
        if (testAdequacy == null || testAdequacy.findings().isEmpty()) {
            return List.of();
        }
        List<Finding> testFindings = testAdequacy.findings();

        // No usable exempt-class judgment → the test-adequacy finding stands.
        if (exempt == null || exempt.answers().isEmpty()) {
            return testFindings;
        }
        // Deterministic mixed-change fail-safe: any non-exempt behavioral hunk ⇒ no exemption, whatever
        // the exempt-class judgment says.
        if (!facts.changeClassification().allRuntimeExempt()) {
            return testFindings;
        }

        BandedAnswer exemptClass = exempt.answers().get("exempt_class");
        BandedAnswer genuineRevert = exempt.answers().get("is_genuine_revert");
        if (exemptClass == null) {
            return testFindings;
        }

        // Sub-CONFIRM class judgment → do not suppress; keep the finding and mark a possible exemption.
        if (!exemptClass.isConfirm()) {
            return annotate(testFindings, "possible exemption (" + displayChoice(exemptClass) + ", unconfirmed)",
                    true);
        }
        // Confident "none" → the test-adequacy finding stands unchanged.
        if (exemptClass.isChoice("none")) {
            return testFindings;
        }

        boolean revert = exemptClass.isChoice("revert") && genuineRevert != null && genuineRevert.isConfirmYes();
        boolean messageOrDeprecation =
                exemptClass.isChoice("message-string-only") || exemptClass.isChoice("deprecation-only");
        if (revert || messageOrDeprecation) {
            // Suppress the test-adequacy finding, but record the exemption visibly (not silent, design §4.0).
            Finding note = Finding.of(Severity.FYI, TEST_ADEQUACY, "test-exempt", summary("test-exempt"))
                    .withRationale("exempt class: " + exemptClass.choice());
            return List.of(note);
        }
        if (exemptClass.isChoice("covered-by-existing-test")) {
            // Never suppresses — downgrades the Blocker to Needs-attention and asks a human to confirm coverage.
            return testFindings.stream().map(this::downgradeToCovered).toList();
        }
        return testFindings;
    }

    private Finding downgradeToCovered(Finding f) {
        Severity severity = f.severity() == Severity.BLOCKER ? Severity.NEEDS_ATTENTION : f.severity();
        return new Finding(severity, f.concern(), f.file(), f.line(),
                summary("test-covered-by-existing"), "test-covered-by-existing", true,
                "an existing untouched test plausibly covers this — confirm it exercises the change",
                f.fixProse());
    }

    // --- inconsistency corroborates breaking-change --------------------------------------------------

    List<Finding> corroborateBreakingChange(DeepResult breaking, DeepResult inconsistency) {
        if (breaking == null || breaking.findings().isEmpty()) {
            return breaking == null ? List.of() : breaking.findings();
        }
        BandedAnswer mismatchKind = inconsistency == null ? null : inconsistency.answers().get("mismatch_kind");
        boolean falseNotBreaking = mismatchKind != null && mismatchKind.isChoice("false-not-breaking-claim");
        if (!falseNotBreaking) {
            return breaking.findings();
        }
        return breaking.findings().stream()
                .map(f -> f.withRationale(appendRationale(f.rationale(),
                        "PR text claims non-breaking — contradicted by the diff (inconsistency)")))
                .toList();
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static void addFindings(List<Finding> acc, DeepResult r) {
        if (r != null) {
            acc.addAll(r.findings());
        }
    }

    private List<Finding> annotate(List<Finding> findings, String note, boolean tentative) {
        return findings.stream()
                .map(f -> f.withRationale(appendRationale(f.rationale(), note)).tentative(f.tentative() || tentative))
                .toList();
    }

    private static String appendRationale(String existing, String added) {
        if (existing == null || existing.isBlank()) {
            return added;
        }
        return existing + "; " + added;
    }

    private static String displayChoice(BandedAnswer a) {
        return a.choice() == null ? "?" : a.choice();
    }

    private String summary(String key) {
        return messages.summary(key);
    }

    /**
     * Collapse to one finding per concern, keeping the most severe; ties break toward the firm (non-tentative)
     * finding, then stably toward the one already seen — so the result is order-independent for severity (I-6).
     */
    static List<Finding> mostSeverePerConcern(List<Finding> findings) {
        Map<String, Finding> byConcern = new LinkedHashMap<>();
        for (Finding f : findings) {
            Finding existing = byConcern.get(f.concern());
            if (existing == null || wins(f, existing)) {
                byConcern.put(f.concern(), f);
            }
        }
        return List.copyOf(byConcern.values());
    }

    private static boolean wins(Finding candidate, Finding incumbent) {
        if (candidate.severity().moreSevereThan(incumbent.severity())) {
            return true;
        }
        if (incumbent.severity().moreSevereThan(candidate.severity())) {
            return false;
        }
        // Same severity: a firm finding beats a tentative one; otherwise keep the incumbent (stable).
        return incumbent.tentative() && !candidate.tentative();
    }
}
