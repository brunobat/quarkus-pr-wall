package io.quarkus.prreview.facts.model;

import java.util.List;

/**
 * One file in the PR diff with its deterministic classification (design §3.1).
 *
 * @param path             repo-relative path (already NFKC-normalized, zero-width stripped by
 *                         {@link ClassifyFiles})
 * @param tag              the {@link FileTag} used for the §5 security floor
 * @param runtimeScope     whether this file is in scope for §4's test requirement — independent of
 *                         {@code tag} (a {@code pom.xml} dependency change is {@code BUILD_CI_SECURITY}
 *                         <em>and</em> runtime-scoped)
 * @param addedLineCount   lines added (diffstat)
 * @param removedLineCount lines removed (diffstat)
 * @param module           best-effort module this file belongs to (for touched-modules reporting)
 * @param hunks            bounded diff hunks
 * @param pathSuspicious   true if the path only matched a classification rule after NFKC
 *                         normalization — itself a flagged anomaly (design §3.1)
 */
public record ChangedFile(
        String path,
        FileTag tag,
        boolean runtimeScope,
        int addedLineCount,
        int removedLineCount,
        String module,
        List<Hunk> hunks,
        boolean pathSuspicious) {

    public ChangedFile {
        hunks = hunks == null ? List.of() : List.copyOf(hunks);
    }

    public boolean isTest() {
        return tag == FileTag.TEST;
    }

    public boolean isSecuritySensitive() {
        return tag == FileTag.BUILD_CI_SECURITY;
    }
}
