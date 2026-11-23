package io.quarkus.prreview.triage.model;

import java.util.function.UnaryOperator;

/**
 * One file changed by a PR, as named in the diff headers. Context only: it is never used to filter or
 * decide anything.
 *
 * @param path the post-change path (the old path for a deleted file)
 * @param kind how the file changed
 * @param oldPath the pre-change path for a rename, otherwise {@code null}
 * @param binary whether the diff carries no text hunks because the file is binary
 */
public record ChangedFile(String path, Kind kind, String oldPath, boolean binary) {

    public enum Kind {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED
    }

    /** E.g. {@code modified}, {@code renamed from a/Old.java}, {@code added, binary}. */
    public String describe() {
        String text = kind == Kind.RENAMED ? "renamed from " + oldPath : kind.name().toLowerCase();
        return binary ? text + ", binary" : text;
    }

    /** The file's line in a changed-file list: {@code - <path> (<describe>)}, with no line break. */
    public String listLine() {
        return listLine(UnaryOperator.identity(), UnaryOperator.identity());
    }

    /** {@link #listLine()} with the path and the description each passed through {@code path} or {@code detail}. */
    public String listLine(UnaryOperator<String> path, UnaryOperator<String> detail) {
        return "- " + path.apply(this.path) + " (" + detail.apply(describe()) + ")";
    }
}
