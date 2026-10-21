package io.quarkus.prreview.facts.model;

import java.util.List;
import java.util.Map;

/**
 * Output of {@link ClassifyChanges} (design §3.1 {@code classify-changes}): per runtime-scoped file,
 * the deterministic exempt signal of §4.0.
 *
 * <p>{@link #allRuntimeExempt()} encodes the §4.0 mixed-change fail-safe: exemption is all-or-nothing
 * across the runtime surface. If <em>any</em> runtime file is {@link ExemptClass#NOT_EXEMPT}, the
 * whole PR is treated as not-exempt and {@code jev} can never override that in the interpretation
 * layer (plan §7 pipeline B, design §4.0).
 */
public record ChangeClassification(Map<String, ExemptClass> perFile) {

    public ChangeClassification {
        perFile = perFile == null ? Map.of() : Map.copyOf(perFile);
    }

    /** True when there is at least one runtime file and none is {@link ExemptClass#NOT_EXEMPT}. */
    public boolean allRuntimeExempt() {
        return !perFile.isEmpty() && perFile.values().stream().noneMatch(e -> e == ExemptClass.NOT_EXEMPT);
    }

    /** True when at least one runtime file carries a non-exempt behavioral hunk. */
    public boolean hasNonExemptRuntimeHunk() {
        return perFile.values().stream().anyMatch(e -> e == ExemptClass.NOT_EXEMPT);
    }

    public List<String> exemptFiles() {
        return perFile.entrySet().stream()
                .filter(e -> e.getValue() != ExemptClass.NOT_EXEMPT)
                .map(Map.Entry::getKey)
                .toList();
    }
}
