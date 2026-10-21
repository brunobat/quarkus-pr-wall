package io.quarkus.prreview.report.services;

import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.review.model.AuthorContext;
import io.quarkus.prreview.review.model.PipelineRun;
import io.quarkus.prreview.review.model.Provenance;
import io.quarkus.prreview.review.model.RangeReport;
import io.quarkus.prreview.review.model.ReviewResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Deterministic markdown rendering of the structured review results (plan §9 item 3). This is pure templating
 * over the already-decided {@link ReviewResult} / {@link RangeReport} — no model call, no severity decisions —
 * so the report is reproducible and the eval suite asserts the structured findings, not this prose.
 *
 * <p>Governing principle (I-6): silence by default. A PR with no findings renders an explicit "nothing met the
 * review threshold" line rather than an empty or alarmed report.
 */
@ApplicationScoped
public class MarkdownRenderer {

    private static final String[] PRIORITY = {"skim", "read-through", "read-closely"};
    private static final String[] BLAST = {
            "isolated", "local", "extension-wide", "cross-cutting", "foundational"};

    // --- single PR ---------------------------------------------------------------------------------

    public String renderPr(ReviewResult r) {
        StringBuilder sb = new StringBuilder();
        String title = r.title() == null ? "" : " — " + r.title();
        sb.append("# PR #").append(r.prNumber()).append(title).append("\n\n");

        if (r.isErrored()) {
            sb.append("> **Could not be reviewed automatically.** ").append(nz(r.note())).append('\n');
            return sb.toString();
        }

        sb.append("**Priority:** ").append(label(PRIORITY, r.priority()))
                .append("  ·  **Blast radius:** ").append(label(BLAST, r.blastRadius()))
                .append("  ·  **Blockers:** ").append(r.blockerCount()).append("\n\n");

        renderAuthorContext(sb, r.authorContext());

        sb.append("## Findings\n\n");
        if (r.findings().isEmpty()) {
            sb.append("_No findings — nothing met the review threshold (silence by default)._\n\n");
        } else {
            for (Finding f : orderBySeverity(r.findings())) {
                renderFinding(sb, f);
            }
        }

        renderPipelines(sb, r.pipelines());
        renderProvenance(sb, r.provenance());
        return sb.toString();
    }

    /**
     * One optional line of attested author context (authorship-provenance design §4.6), e.g.
     * {@code Author: alice (committer) · AI assistance declared}.
     *
     * <p>Three things it never does. It never says a declaration is missing — that is the normal case
     * and carries no information. It never names the tool, because the project's interest is that
     * assistance happened, not which vendor. And it never appears next to a severity, because a
     * declaration is not a finding and must not be read as one (I-7).
     */
    private void renderAuthorContext(StringBuilder sb, AuthorContext a) {
        if (a == null || !a.hasAnythingToSay()) {
            return;
        }
        sb.append("**Author:** ").append(a.login());
        if (a.isCommitter()) {
            sb.append(" (committer)");
        } else if (a.authorIsAllowlistedBot()) {
            sb.append(" (routine automation)");
        } else if (a.authorIsBot()) {
            sb.append(" (bot account)");
        }
        if (a.declaresAiAssistance()) {
            sb.append("  ·  AI assistance declared");
        }
        sb.append("\n\n");
    }

    private void renderFinding(StringBuilder sb, Finding f) {
        sb.append("- **[").append(severityLabel(f.severity())).append("]** ").append(f.summary());
        if (f.file() != null) {
            sb.append(" (`").append(f.file());
            if (f.line() > 0) {
                sb.append(':').append(f.line());
            }
            sb.append("`)");
        }
        if (f.tentative()) {
            sb.append(" _(human confirm)_");
        }
        sb.append('\n');
        if (f.fixProse() != null && !f.fixProse().isBlank()) {
            sb.append("  - _fix:_ ").append(f.fixProse()).append('\n');
        }
        if (f.rationale() != null && !f.rationale().isBlank()) {
            sb.append("  - _why:_ ").append(f.rationale()).append('\n');
        }
    }

    private void renderPipelines(StringBuilder sb, List<PipelineRun> runs) {
        if (runs.isEmpty()) {
            return;
        }
        sb.append("\n## Pipelines\n\n");
        sb.append("| concern | status | note |\n|---|---|---|\n");
        for (PipelineRun p : runs) {
            sb.append("| ").append(p.concern())
                    .append(" | ").append(p.status())
                    .append(" | ").append(nz(p.note())).append(" |\n");
        }
    }

    private void renderProvenance(StringBuilder sb, Provenance p) {
        if (p == null) {
            return;
        }
        sb.append("\n## Provenance\n\n");
        sb.append("- head: `").append(nz(p.headSha())).append("`\n");
        sb.append("- jev model: `").append(nz(p.jevModel())).append("`\n");
        sb.append("- opus model: `").append(nz(p.opusModel())).append("`\n");
        sb.append("- triage questions: `").append(nz(p.triageQuestionVersion())).append("`\n");
        sb.append("- ruleset: `").append(nz(p.rulesetVersion()))
                .append("`  ·  thresholds: `").append(nz(p.thresholdsVersion())).append("`\n");
    }

    // --- range -------------------------------------------------------------------------------------

    public String renderRange(RangeReport report) {
        StringBuilder sb = new StringBuilder();
        int total = report.ranked().size() + report.errored().size();
        sb.append("# Range review — ").append(total).append(" PR").append(total == 1 ? "" : "s").append("\n\n");

        sb.append("## Ranked\n\n");
        if (report.ranked().isEmpty()) {
            sb.append("_No PRs reviewed._\n\n");
        } else {
            sb.append("| PR | Title | Priority | Blast | Blockers | Top finding |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (ReviewResult r : report.ranked()) {
                sb.append("| #").append(r.prNumber())
                        .append(" | ").append(truncate(nz(r.title()), 48))
                        .append(" | ").append(label(PRIORITY, r.priority()))
                        .append(" | ").append(label(BLAST, r.blastRadius()))
                        .append(" | ").append(r.blockerCount())
                        .append(" | ").append(topFinding(r)).append(" |\n");
            }
        }

        if (!report.errored().isEmpty()) {
            sb.append("\n## Could not be reviewed automatically\n\n");
            sb.append("| PR | Status | Note |\n|---|---|---|\n");
            for (ReviewResult r : report.errored()) {
                sb.append("| #").append(r.prNumber())
                        .append(" | ").append(r.status())
                        .append(" | ").append(truncate(nz(r.note()), 80)).append(" |\n");
            }
        }
        return sb.toString();
    }

    private static String topFinding(ReviewResult r) {
        return orderBySeverity(r.findings()).stream()
                .findFirst()
                .map(f -> "[" + severityLabel(f.severity()) + "] " + truncate(f.summary(), 48))
                .orElse("—");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static List<Finding> orderBySeverity(List<Finding> findings) {
        return findings.stream()
                .sorted((a, b) -> Integer.compare(a.severity().ordinal(), b.severity().ordinal()))
                .toList();
    }

    private static String severityLabel(Severity s) {
        return switch (s) {
            case BLOCKER -> "BLOCKER";
            case NEEDS_ATTENTION -> "NEEDS-ATTENTION";
            case FYI -> "FYI";
            case SILENCE -> "SILENCE";
        };
    }

    private static String label(String[] labels, int level) {
        if (level < 0 || level >= labels.length) {
            return String.valueOf(level);
        }
        return level + " (" + labels[level] + ")";
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
