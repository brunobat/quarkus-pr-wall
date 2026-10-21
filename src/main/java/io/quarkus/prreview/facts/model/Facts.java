package io.quarkus.prreview.facts.model;

import java.util.List;

/**
 * The deterministic facts layer output (design §3.1): the ground truth the LLM must not guess and PR
 * text must not be able to game. Each pipeline reads only the slice of this it needs (plan §4).
 *
 * <p>Trust/identity facts ({@link #committerStatus}, {@link #authorHistory}) are pinned to
 * {@code base.sha} / the platform API and are <strong>never</strong> placed in a {@code jev} state —
 * they are applied as deterministic gates in the interpretation layer only (invariants I-2, I-3).
 *
 * <p>{@link #provenance} records what the PR declares about its own authorship. It may only route a PR
 * to a deeper look, never penalise it (invariant I-7).
 */
public record Facts(
        PullRequest pr,
        List<ChangedFile> files,
        ChangeClassification changeClassification,
        List<TestFacts> tests,
        CommitterStatus committerStatus,
        AuthorHistory authorHistory,
        boolean isBackport,
        AuthorshipProvenance provenance) {

    public Facts {
        files = files == null ? List.of() : List.copyOf(files);
        tests = tests == null ? List.of() : List.copyOf(tests);
        provenance = provenance == null ? AuthorshipProvenance.unknown() : provenance;
    }

    public boolean hasRuntimeChange() {
        return files.stream().anyMatch(ChangedFile::runtimeScope);
    }

    public boolean hasSecuritySensitiveFile() {
        return files.stream().anyMatch(ChangedFile::isSecuritySensitive);
    }

    /** True when the diff touches at least one docs file — the docs-missing escalation reads this. */
    public boolean hasDocsFile() {
        return files.stream().anyMatch(f -> f.tag() == FileTag.DOCS);
    }

    public boolean touchesExistingTest() {
        return files.stream().anyMatch(f -> f.isTest() && f.removedLineCount() > 0);
    }

    public boolean firstTimerTouchingSecurity() {
        return committerStatus.isFirstTimer() && hasSecuritySensitiveFile();
    }

    public List<ChangedFile> runtimeFiles() {
        return files.stream().filter(ChangedFile::runtimeScope).toList();
    }

    public List<ChangedFile> testFiles() {
        return files.stream().filter(ChangedFile::isTest).toList();
    }
}
