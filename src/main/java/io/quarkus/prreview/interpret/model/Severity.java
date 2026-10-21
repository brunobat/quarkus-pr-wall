package io.quarkus.prreview.interpret.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * Finding severity (design §7, plan §6), ordered most-severe first. {@link #SILENCE} is the default:
 * high threshold / high signal — a finding is emitted only when earned (invariant I-6).
 */
public enum Severity {
    BLOCKER,
    NEEDS_ATTENTION,
    FYI,
    SILENCE;

    /** True if {@code this} is strictly more severe than {@code other} (BLOCKER is most severe). */
    public boolean moreSevereThan(Severity other) {
        return this.ordinal() < other.ordinal();
    }

    public boolean isEmitted() {
        return this != SILENCE;
    }

    /** Accept YAML spellings: {@code blocker}, {@code needs-attention}/{@code needs_attention}, {@code fyi}. */
    @JsonCreator
    public static Severity fromString(String s) {
        return Severity.valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
