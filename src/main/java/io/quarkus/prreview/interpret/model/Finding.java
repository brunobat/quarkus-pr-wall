package io.quarkus.prreview.interpret.model;

/**
 * A structured finding (design §10): the intermediate representation that evals assert against, before
 * any markdown rendering, so wording/model drift never breaks the eval suite.
 *
 * <p>{@code fixProse} is filled in later by Opus (plan §9), strictly downstream of this structured
 * decision; the gate has already set {@code severity} by the time Opus sees the finding.
 *
 * @param severity   Blocker / Needs-attention / FYI (never SILENCE — a silenced concern produces no
 *                   finding at all)
 * @param concern    concern key, e.g. {@code "test-adequacy"}, {@code "breaking-change"}
 * @param file       {@code file} for {@code file:line} anchoring (may be null for PR-wide findings)
 * @param line       1-indexed line, or 0 if not line-anchored
 * @param summary    one-sentence structured summary (asserted by evals)
 * @param messageKey key into the message catalog Opus renders prose from
 * @param tentative  true when emitted at FLAG band — rendered "human confirm" (plan §6)
 * @param rationale  visible rationale (e.g. exemption class, cited test) — never silent (design §4.0)
 * @param fixProse   Opus-generated "do X here" line, or null until the report stage
 */
public record Finding(
        Severity severity,
        String concern,
        String file,
        int line,
        String summary,
        String messageKey,
        boolean tentative,
        String rationale,
        String fixProse) {

    public static Finding of(Severity severity, String concern, String messageKey, String summary) {
        return new Finding(severity, concern, null, 0, summary, messageKey, false, null, null);
    }

    public Finding at(String file, int line) {
        return new Finding(severity, concern, file, line, summary, messageKey, tentative, rationale, fixProse);
    }

    public Finding tentative(boolean t) {
        return new Finding(severity, concern, file, line, summary, messageKey, t, rationale, fixProse);
    }

    public Finding withRationale(String r) {
        return new Finding(severity, concern, file, line, summary, messageKey, tentative, r, fixProse);
    }

    public Finding withFixProse(String prose) {
        return new Finding(severity, concern, file, line, summary, messageKey, tentative, rationale, prose);
    }
}
