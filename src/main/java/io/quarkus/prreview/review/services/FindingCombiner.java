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
 *   <li><b>A + B</b> — the exempt-class judgment (B) may suppress or downgrade the test-adequacy finding (A),
 *       but only behind the deterministic mixed-change fail-safe (design §4.0): if any runtime hunk is
 *       deterministically not-exempt, no exemption applies whatever B says.</li>
 *   <li><b>G corroborates C</b> — a {@code false-not-breaking-claim} from G strengthens C's rationale; it never
 *       creates or escalates C (G never feeds C, plan §7G).</li>
 * </ul>
 *
 * Everything is pure over the pipeline outputs + pinned facts, so it is unit-tested with hand-authored results.
 */
@ApplicationScoped
public class FindingCombiner {

    @Inject
    MessageCatalog messages;

    /** Produce the final finding list for a PR from triage + the deep results that ran. */
    public List<Finding> combine(TriageOutcome triage, Map<String, DeepResult> deep, Facts facts) {
        List<Finding> all = new ArrayList<>(triage.decisions().findings());

        // A + B coupling (the one place A and B meet).
        all.addAll(combineAB(deep.get("A"), deep.get("B"), facts));

        // C, strengthened by G's corroboration but never created by it.
        all.addAll(corroborateC(deep.get("C"), deep.get("G")));

        // Remaining pipelines contribute their findings directly.
        addFindings(all, deep.get("D"));
        addFindings(all, deep.get("E"));
        addFindings(all, deep.get("F"));
        addFindings(all, deep.get("G"));

        return mostSeverePerConcern(all);
    }

    // --- A + B -------------------------------------------------------------------------------------

    List<Finding> combineAB(DeepResult a, DeepResult b, Facts facts) {
        if (a == null || a.findings().isEmpty()) {
            return List.of();
        }
        List<Finding> aFindings = a.findings();

        // No usable B judgment → A stands.
        if (b == null || b.answers().isEmpty()) {
            return aFindings;
        }
        // Deterministic mixed-change fail-safe: any non-exempt behavioral hunk ⇒ no exemption, whatever B says.
        if (!facts.changeClassification().allRuntimeExempt()) {
            return aFindings;
        }

        BandedAnswer exemptClass = b.answers().get("exempt_class");
        BandedAnswer genuineRevert = b.answers().get("is_genuine_revert");
        if (exemptClass == null) {
            return aFindings;
        }

        // Sub-CONFIRM class judgment → do not suppress; leave A and mark a possible exemption.
        if (!exemptClass.isConfirm()) {
            return annotate(aFindings, "possible exemption (" + displayChoice(exemptClass) + ", unconfirmed)", true);
        }
        // Confident "none" → A stands unchanged.
        if (exemptClass.isChoice("none")) {
            return aFindings;
        }

        boolean revert = exemptClass.isChoice("revert") && genuineRevert != null && genuineRevert.isConfirmYes();
        boolean messageOrDeprecation =
                exemptClass.isChoice("message-string-only") || exemptClass.isChoice("deprecation-only");
        if (revert || messageOrDeprecation) {
            // Suppress A's finding, but record the exemption visibly (not silent, design §4.0).
            Finding note = Finding.of(Severity.FYI, "test-adequacy", "test-exempt", summary("test-exempt"))
                    .withRationale("exempt class: " + exemptClass.choice());
            return List.of(note);
        }
        if (exemptClass.isChoice("covered-by-existing-test")) {
            // Never suppresses — downgrades A's Blocker to Needs-attention and asks a human to confirm coverage.
            return aFindings.stream().map(this::downgradeToCovered).toList();
        }
        return aFindings;
    }

    private Finding downgradeToCovered(Finding f) {
        Severity severity = f.severity() == Severity.BLOCKER ? Severity.NEEDS_ATTENTION : f.severity();
        return new Finding(severity, f.concern(), f.file(), f.line(),
                summary("test-covered-by-existing"), "test-covered-by-existing", true,
                "an existing untouched test plausibly covers this — confirm it exercises the change",
                f.fixProse());
    }

    // --- G corroborates C --------------------------------------------------------------------------

    List<Finding> corroborateC(DeepResult c, DeepResult g) {
        if (c == null || c.findings().isEmpty()) {
            return c == null ? List.of() : c.findings();
        }
        BandedAnswer mismatchKind = g == null ? null : g.answers().get("mismatch_kind");
        boolean falseNotBreaking = mismatchKind != null && mismatchKind.isChoice("false-not-breaking-claim");
        if (!falseNotBreaking) {
            return c.findings();
        }
        return c.findings().stream()
                .map(f -> f.withRationale(appendRationale(f.rationale(),
                        "PR text claims non-breaking — contradicted by the diff (G)")))
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
