package io.quarkus.prreview.report.summary.model;

/**
 * The prose summary of a classification.
 *
 * @param claudeModel the Claude model that wrote it, or {@code null} for the fallback template
 */
public record Summary(String text, String claudeModel) {

    /** Where the text came from: {@code Claude (<model>)} or {@code fallback template}. */
    public String source() {
        return claudeModel == null ? "fallback template" : "Claude (" + claudeModel + ")";
    }

    /** The short source shown in the terminal: {@code Claude} or {@code fallback template}. */
    public String shortSource() {
        return claudeModel == null ? "fallback template" : "Claude";
    }
}
