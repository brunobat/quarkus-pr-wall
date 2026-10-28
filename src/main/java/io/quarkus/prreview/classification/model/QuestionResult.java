package io.quarkus.prreview.classification.model;

import java.util.Map;

/** One question and {@code jev}'s answer to it, unchanged. Its check is the enclosing {@link CheckResult}. */
/**
 * @param label the short name shown in the output, e.g. {@code behaviour}
 * @param text the question as asked
 */
public record QuestionResult(String label, String text, Answer answer) {

    /** {@code jev}'s answer, by question kind. */
    public sealed interface Answer permits YesNo, Choice {
    }

    /** @param probability {@code jev}'s probability of "yes", as returned */
    public record YesNo(double probability) implements Answer {
    }

    /**
     * @param value {@code jev}'s chosen option, as returned; never picked by the app
     * @param options every option offered, with its description, in the order the question declares them
     * @param probabilities {@code jev}'s probability for each option it reported, in the order of {@code options};
     *        empty if {@code jev} reported none
     */
    public record Choice(String value, Map<String, String> options, Map<String, Double> probabilities)
            implements Answer {
    }
}
