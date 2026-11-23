package io.quarkus.prreview.classification;

/**
 * What one classification source found for a PR: its facts, or that they could not be obtained. Every PR has a
 * result from every source, whatever happened to the others. Why a source was unavailable is in its one warning
 * line, not here.
 *
 * @param <T> the source's own fact type
 */
public sealed interface SourceResult<T> permits SourceResult.Available, SourceResult.Unavailable {

    /** @param facts the source's facts, as obtained */
    record Available<T>(T facts) implements SourceResult<T> {
    }

    /** The facts could not be obtained. */
    record Unavailable<T>() implements SourceResult<T> {
    }

    static <T> SourceResult<T> unavailable() {
        return new Unavailable<>();
    }
}
