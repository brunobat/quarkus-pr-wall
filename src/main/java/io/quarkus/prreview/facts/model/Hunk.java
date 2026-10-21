package io.quarkus.prreview.facts.model;

import java.util.List;

/**
 * A bounded diff hunk for one file. {@code addedLines}/{@code removedLines} carry the actual
 * {@code +}/{@code -} content (prefix stripped) so the deterministic exempt-signal detection in
 * {@link ClassifyChanges} and the {@code jev} state builders can read the change without re-parsing
 * raw unified-diff text. {@code startLine} is the new-file start line for {@code file:line} anchoring
 * in findings (design §7).
 */
public record Hunk(
        int startLine,
        List<String> addedLines,
        List<String> removedLines) {

    public Hunk {
        addedLines = addedLines == null ? List.of() : List.copyOf(addedLines);
        removedLines = removedLines == null ? List.of() : List.copyOf(removedLines);
    }

    public boolean isPureDeletion() {
        return addedLines.isEmpty() && !removedLines.isEmpty();
    }
}
