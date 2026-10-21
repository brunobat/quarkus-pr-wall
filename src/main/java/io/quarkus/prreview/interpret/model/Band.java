package io.quarkus.prreview.interpret.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * The confidence band a {@code jev} answer falls into (plan §6). The gate is <em>asymmetric</em>:
 * only a {@link #CONFIRM}-band "no issue" may clear a concern to silence; any sub-CONFIRM answer on a
 * risk question routes to at least {@link #FLAG} (invariant I-5).
 */
public enum Band {
    /** Act on the typed answer directly, including auto-clearing to silence when the answer is "no issue". */
    CONFIRM,
    /** Emit the finding, marked tentative "human confirm". */
    FLAG,
    /** Escalate to a human-review note — never clears a concern. */
    HUMAN;

    @JsonCreator
    public static Band fromString(String s) {
        return Band.valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
