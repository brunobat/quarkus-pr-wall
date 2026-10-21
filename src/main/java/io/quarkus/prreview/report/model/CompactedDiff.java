package io.quarkus.prreview.report.model;

import java.util.List;

/**
 * The result of fitting an oversized diff into the {@code jev} state budget (plan §9.4). Compaction is
 * the one Opus call that runs <em>before</em> {@code jev} rather than downstream of the gate, so it is
 * the one place Opus could degrade a decision — hence every result is explicitly labelled lossy and
 * names the files whose hunks were summarized away.
 *
 * <p>Only free-text diff body is ever compacted. The deterministic facts (the {@code files}/{@code tests}
 * blocks, exempt classification, committer status) are passed through verbatim and are never summarized.
 *
 * @param text             the diff text to place in the state
 * @param applied          true when the original exceeded the budget and was compacted
 * @param method           {@code verbatim} · {@code focal-only} · {@code opus} · {@code deterministic}
 * @param originalChars    size of the input diff
 * @param summarizedFiles  paths whose hunks were replaced by a summary (empty when {@code !applied})
 */
public record CompactedDiff(
        String text,
        boolean applied,
        String method,
        int originalChars,
        List<String> summarizedFiles) {

    public CompactedDiff {
        summarizedFiles = summarizedFiles == null ? List.of() : List.copyOf(summarizedFiles);
    }

    public static CompactedDiff verbatim(String text) {
        return new CompactedDiff(text, false, "verbatim", text == null ? 0 : text.length(), List.of());
    }
}
