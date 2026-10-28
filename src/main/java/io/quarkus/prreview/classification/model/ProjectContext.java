package io.quarkus.prreview.classification.model;

/**
 * The fixed prose that starts every {@code jev} input: what the repository is and its layout conventions. It is the
 * same for every PR and names no answer.
 *
 * @param source where it was read from, as configured ({@code JEV_PROJECT_CONTEXT})
 * @param text the context, stripped
 */
public record ProjectContext(String source, String text) {
}
