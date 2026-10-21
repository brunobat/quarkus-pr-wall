package io.quarkus.prreview.facts.model;

import java.util.List;

/**
 * Raw per-file result of {@link DiffParser}, before classification. {@link ClassifyFiles} turns this
 * into a tagged {@link ChangedFile}.
 */
public record ParsedFile(
        String path,
        List<Hunk> hunks,
        int addedLineCount,
        int removedLineCount,
        boolean newFile,
        boolean deletedFile) {

    public ParsedFile {
        hunks = hunks == null ? List.of() : List.copyOf(hunks);
    }
}
