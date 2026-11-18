package io.quarkus.prreview.classification.source.model;

/**
 * What one classification source found for a PR: its facts, or the reason they could not be obtained. Every PR has a
 * result from every source, whatever happened to the others.
 *
 * @param <T> the source's own fact type
 */
public sealed interface SourceResult<T> permits SourceResult.Available, SourceResult.Unavailable {

    /** @param facts the source's facts, as obtained */
    record Available<T>(T facts) implements SourceResult<T> {
    }

    /** @param reason why the facts could not be obtained, one line */
    record Unavailable<T>(String reason) implements SourceResult<T> {
    }

    static <T> SourceResult<T> unavailable(String reason) {
        return new Unavailable<>(reason);
    }
}
