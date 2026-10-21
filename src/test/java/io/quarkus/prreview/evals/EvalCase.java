package io.quarkus.prreview.evals;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One eval fixture (plan §10, §13): a recorded PR (metadata + diff + author context) paired with the
 * {@code jev} answers that PR would draw, and the structured findings the agent is expected to produce.
 *
 * <p>Expectations are asserted against the {@link io.quarkus.prreview.review.model.ReviewResult} IR —
 * severity, concern and message key — never against rendered prose, so model wording drift cannot break
 * the suite.
 *
 * @param name        stable case id, also the fixture file name
 * @param description what the case is meant to pin down
 * @param clean       true when this PR should produce <em>no</em> findings — the false-positive corpus
 * @param fullDepth   run every deep pipeline (single-PR mode) vs. prune to the triage escalation set (range mode)
 * @param pr          PR metadata served to the stubbed {@code gh pr view}
 * @param author      author context served to {@code gh search prs} / {@code gh api users} / COMMITTERS
 * @param diffFile    sibling {@code .diff} file holding the recorded unified diff
 * @param answers     canned {@code jev} answers by question id; ids the case omits come back unanswered
 * @param expect      the expected structured outcome
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String name,
        String description,
        boolean clean,
        Boolean fullDepth,
        Pr pr,
        Author author,
        String diffFile,
        Map<String, FixtureAnswer> answers,
        Expect expect) {

    public EvalCase {
        answers = answers == null ? Map.of() : Map.copyOf(answers);
    }

    public boolean runFullDepth() {
        return fullDepth == null || fullDepth;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pr(
            int number,
            String title,
            String body,
            List<String> labels,
            String author,
            String baseBranch,
            String baseSha,
            String headSha) {

        public Pr {
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }

    /**
     * @param mergedPrs      prior merged PRs by this author (0 ⇒ first-timer once committer status resolves)
     * @param accountAgeDays account age; &lt; 30 trips the I-2 brand-new-account anomaly
     * @param committer      whether the author appears in {@code COMMITTERS.adoc} at {@code baseSha}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(int mergedPrs, long accountAgeDays, boolean committer) {
    }

    /**
     * @param priority        expected effective review-priority 0–2, or null to not assert it
     * @param minBlastRadius  lower bound on the effective blast radius, or null
     * @param findings        every finding that must be present
     * @param absentConcerns  concerns that must produce no finding at all
     * @param exactly         when true, {@code findings} must be the complete set, concern for concern
     * @param pipelinesRun    deep pipelines that must have run, exactly — the range-mode escalation set
     *                        (§5.4d); null to not assert it
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(
            Integer priority,
            Integer minBlastRadius,
            List<ExpectedFinding> findings,
            List<String> absentConcerns,
            boolean exactly,
            List<String> pipelinesRun) {

        public Expect {
            findings = findings == null ? List.of() : List.copyOf(findings);
            absentConcerns = absentConcerns == null ? List.of() : List.copyOf(absentConcerns);
        }
    }

    /**
     * @param concern    required
     * @param severity   required, one of BLOCKER / NEEDS_ATTENTION / FYI
     * @param messageKey optional — asserted when present
     * @param tentative  optional — asserted when present (a FLAG-band "human confirm" finding)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedFinding(String concern, String severity, String messageKey, Boolean tentative) {
    }

    /**
     * A {@code jev} answer in fixture shorthand: whichever of {@code choice} / {@code score} / {@code noul}
     * is set determines the answer type, matching the three types the API returns (plan §2).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FixtureAnswer(String choice, Double score, Double noul, Double confidence) {

        public String type() {
            if (noul != null) {
                return "noul";
            }
            return choice != null ? "choice" : "score";
        }
    }
}
