package io.quarkus.prreview.report.summary.model;

import dev.langchain4j.model.output.TokenUsage;

/**
 * The prose summary of a classification.
 *
 * @param claudeModel the Claude model that wrote it, or {@code null} for the fallback template
 * @param claudeChars the characters of the system and user messages sent to Claude, or {@code null} when Claude
 *        was not called; kept when the call failed and the fallback template is used
 * @param claudeTokens Claude's reported token usage, or {@code null} when not reported
 */
public record Summary(String text, String claudeModel, Integer claudeChars, TokenUsage claudeTokens) {

    /** A summary for which Claude was not called. */
    public Summary(String text, String claudeModel) {
        this(text, claudeModel, null, null);
    }

    /** Where the text came from: {@code Claude (<model>)} or {@code fallback template}. */
    public String source() {
        return claudeModel == null ? "fallback template" : "Claude (" + claudeModel + ")";
    }

    /** The short source shown in the terminal: {@code Claude} or {@code fallback template}. */
    public String shortSource() {
        return claudeModel == null ? "fallback template" : "Claude";
    }
}
