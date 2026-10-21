package io.quarkus.prreview.interpret.rules.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * The closed, safe-by-construction set of effect verbs a rule may have (plan §5.6). There is
 * deliberately <strong>no</strong> "lower priority" verb and <strong>no</strong> unconditional
 * "silence": every safety-relevant effect is monotonic ({@code raise}/{@code floor}/emit-most-severe),
 * which makes the engine confluent for the dangerous directions — rule order cannot change a finding or
 * a priority. A tunable rule physically cannot express a lowering or unguarded-silence effect because
 * the verb does not exist.
 */
public enum RuleEffect {
    /** Raise the effective review-priority to at least {@code value} (monotonic max). */
    raisePriority,
    /** Floor the effective review-priority to {@code value} (monotonic max; synonym for raise). */
    floorPriority,
    /** Emit a finding; "most severe per concern wins", so adding a rule can only raise a verdict. */
    emit,
    /** Add a deep pipeline to the reserved set (additive only). */
    escalate,
    /** Clear a concern to silence — GUARDED: honored only on a CONFIRM-band "no issue" (I-5). */
    silence;

    @JsonCreator
    public static RuleEffect fromString(String s) {
        String v = s.trim();
        for (RuleEffect e : values()) {
            if (e.name().equalsIgnoreCase(v)) {
                return e;
            }
        }
        // tolerate snake/kebab spellings
        String norm = v.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        for (RuleEffect e : values()) {
            if (e.name().toLowerCase(Locale.ROOT).equals(norm)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown effect verb: " + s);
    }
}
