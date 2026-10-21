package io.quarkus.prreview.facts.model;

/**
 * Author's committer status resolved against {@code COMMITTERS.adoc} <em>at {@code base.sha}</em>
 * (design §3.1, §8 invariant I-3) — never from the PR's own head or diff, so a PR cannot rewrite the
 * file it is being judged against.
 *
 * @param status        committer / contributor / first-timer, or {@link Level#UNKNOWN} if the fact
 *                      could not be resolved (degraded mode — never a silent pass, design §9)
 * @param resolvedAtSha the base SHA the status was pinned to
 */
public record CommitterStatus(Level status, String resolvedAtSha) {

    public enum Level {
        COMMITTER,
        CONTRIBUTOR,
        FIRST_TIMER,
        UNKNOWN
    }

    public boolean isCommitter() {
        return status == Level.COMMITTER;
    }

    /** Treated as a non-committer for the §4 Blocker when committer status is not positively known. */
    public boolean isNonCommitter() {
        return status == Level.CONTRIBUTOR || status == Level.FIRST_TIMER || status == Level.UNKNOWN;
    }

    public boolean isFirstTimer() {
        return status == Level.FIRST_TIMER;
    }

    public static CommitterStatus unknown() {
        return new CommitterStatus(Level.UNKNOWN, null);
    }
}
