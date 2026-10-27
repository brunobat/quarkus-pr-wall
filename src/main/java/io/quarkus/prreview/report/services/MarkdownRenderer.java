package io.quarkus.prreview.report.services;

import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.model.Finding;
import io.quarkus.prreview.interpret.model.Severity;
import io.quarkus.prreview.jev.model.Answer;
import io.quarkus.prreview.review.model.AuthorContext;
import io.quarkus.prreview.review.model.PipelineRun;
import io.quarkus.prreview.review.model.Provenance;
import io.quarkus.prreview.review.model.RangeReport;
import io.quarkus.prreview.review.model.ReviewResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /**
     * What the Author column means — and, more importantly, what it does not. The caveat is printed
     * rather than kept in the javadoc because the column's whole risk is being read as a detector:
     * `human` is the bucket a PR falls into when nothing was declared, which is also what every
     * undeclared AI-assisted PR looks like.
     */
    private static final String AUTHOR_LEGEND =
            "Author is derived from attested facts only — the account's GitHub type, the routine-automation "
            + "allowlist, and an AI-assistance marker the author chose to leave. `bot` is allowlisted "
            + "automation, `agent` is a bot account that is not, `human+ai` declared assistance, and "
            + "**`human` means no declaration, not no AI** — nothing inspects the code. `+commits` marks a "
            + "bot- or agent-opened PR that carries a human's commits.";

    /** What the `p` column means, spelled out where the two question types disagree about it. */
    private static final String P_LEGEND =
            "`p` is the probability of the answer as stated. A noul question returns p(yes), so a `no` "
            + "answer shows 1 − p(yes); a score question has no probability for its value, and the number "
            + "shown there is the reported confidence.";

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
        renderDecisions(sb, r.triageAnswers());
        renderProvenance(sb, r.provenance());
        return sb.toString();
    }

    /**
     * Every triage decision with the probability behind it, so a surprising finding can be traced to the
     * answer that produced it without re-running anything.
     *
     * <p>This is the only place the raw probabilities are visible. They are still never read as a second
     * gate decision — policy sees the band and nothing else — but a report that shows only the band asks
     * the reader to trust a threshold they cannot see the input to, and makes band calibration
     * impossible to argue about from output alone.
     */
    private void renderDecisions(StringBuilder sb, Map<String, BandedAnswer> answers) {
        if (answers.isEmpty()) {
            return;
        }
        sb.append("\n## Triage decisions\n\n");
        sb.append("| question | type | answer | p | band |\n|---|---|---|---|---|\n");
        for (Map.Entry<String, BandedAnswer> e : answers.entrySet()) {
            BandedAnswer a = e.getValue();
            sb.append("| ").append(e.getKey())
                    .append(" | ").append(type(a))
                    .append(" | ").append(answerOf(a))
                    .append(" | ").append(probabilityOf(a))
                    .append(" | ").append(a.band()).append(" |\n");
        }
        sb.append('\n').append(P_LEGEND).append('\n');
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
        if (a.hasHumanCommitAuthor() && (a.authorIsBot() || a.authorIsAllowlistedBot())) {
            // Only worth saying on a machine-opened PR, where it contradicts the account type.
            sb.append("  ·  carries human-authored commits");
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
            sb.append("| PR | Title | Author | Priority | Blast | Blockers | Top finding |\n");
            sb.append("|---|---|---|---|---|---|---|\n");
            for (ReviewResult r : report.ranked()) {
                sb.append("| #").append(r.prNumber())
                        .append(" | ").append(truncate(nz(r.title()), 48))
                        .append(" | ").append(authorCell(r.authorContext()))
                        .append(" | ").append(label(PRIORITY, r.priority()))
                        .append(" | ").append(label(BLAST, r.blastRadius()))
                        .append(" | ").append(r.blockerCount())
                        .append(" | ").append(topFinding(r)).append(" |\n");
            }
            sb.append('\n').append(AUTHOR_LEGEND).append('\n');
        }

        renderDecisionMatrix(sb, report.ranked());

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

    /**
     * One row per PR, one column per triage question — the sweep's comparative view.
     *
     * <p>The ranked table above says what the agent concluded; this says what it believed. Reading a
     * column down is how you see that the same answer on near-identical PRs came back at different
     * confidences, which no single-PR report can show.
     *
     * <p>Columns are the union of the questions answered, in the order the gate returned them for the
     * first PR — a PR missing a question (an earlier question-set version, a partial response) leaves
     * an empty cell rather than shifting its neighbours.
     */
    private void renderDecisionMatrix(StringBuilder sb, List<ReviewResult> ranked) {
        List<String> questions = questionColumns(ranked);
        if (questions.isEmpty()) {
            return;
        }
        sb.append("\n## Triage decisions\n\n");
        sb.append("| PR |");
        questions.forEach(q -> sb.append(' ').append(q).append(" |"));
        sb.append("\n|---|").append("---|".repeat(questions.size())).append('\n');

        for (ReviewResult r : ranked) {
            sb.append("| #").append(r.prNumber()).append(" |");
            for (String q : questions) {
                sb.append(' ').append(cell(r.triageAnswers().get(q))).append(" |");
            }
            sb.append('\n');
        }
        sb.append('\n').append(P_LEGEND).append('\n');
        sb.append("Band is marked on the value: `~` FLAG, `?` HUMAN, unmarked CONFIRM.\n");
    }

    /** The union of answered question ids, first-seen order preserved. */
    private static List<String> questionColumns(List<ReviewResult> ranked) {
        Set<String> columns = new LinkedHashSet<>();
        for (ReviewResult r : ranked) {
            columns.addAll(r.triageAnswers().keySet());
        }
        return List.copyOf(columns);
    }

    /** {@code answer p} plus a band marker, compact enough for a fifteen-column table. */
    private static String cell(BandedAnswer a) {
        if (a == null) {
            return "";
        }
        return answerOf(a) + " " + probabilityOf(a) + bandMark(a);
    }

    private static String bandMark(BandedAnswer a) {
        return switch (a.band()) {
            case CONFIRM -> "";
            case FLAG -> "~";
            case HUMAN -> "?";
        };
    }

    private static String type(BandedAnswer a) {
        if (a.raw() == null) {
            return "";
        }
        return nz(a.raw().type());
    }

    private static String answerOf(BandedAnswer a) {
        if (a.choice() != null) {
            return a.choice();
        }
        if (!Double.isNaN(a.score())) {
            return String.format(Locale.ROOT, "%.1f", a.score());
        }
        return a.yes() ? "yes" : "no";
    }

    /**
     * The probability of the answer as stated, which is not always what the model returned: a noul
     * question returns p(yes), so a {@code no} answer reports {@code 1 − p(yes)}. A score question has no
     * probability for its value at all — the number there is the reported confidence, and {@link #P_LEGEND}
     * says so rather than letting the column imply otherwise.
     */
    private static String probabilityOf(BandedAnswer a) {
        Answer raw = a.raw();
        if (raw == null) {
            return "";
        }
        double p;
        if (raw.isNoul()) {
            p = a.yes() ? raw.noulValue() : 1.0 - raw.noulValue();
        } else if (a.choice() != null && raw.probabilities() != null) {
            p = raw.probabilities().getOrDefault(a.choice(), raw.derivedConfidence());
        } else {
            p = raw.derivedConfidence();
        }
        return String.format(Locale.ROOT, "%.2f", p);
    }

    /**
     * {@code login (kind)} for the sweep, e.g. {@code dependabot (bot)}.
     *
     * <p>The kind is shown even when the login is unknown, because it is the column being scanned; an
     * absent {@link AuthorContext} renders an em dash rather than guessing {@code human}, which is the
     * one wrong answer here — the residual bucket is for PRs we have facts about.
     */
    private static String authorCell(AuthorContext a) {
        if (a == null) {
            return "—";
        }
        StringBuilder cell = new StringBuilder();
        if (a.login() != null && !a.login().isBlank()) {
            cell.append(a.login()).append(' ');
        }
        cell.append('(').append(a.kind().label());
        if (a.hasHumanCommitAuthor() && (a.authorIsBot() || a.authorIsAllowlistedBot())) {
            cell.append(" +commits");
        }
        return cell.append(')').toString();
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
