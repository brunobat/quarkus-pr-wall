package io.quarkus.prreview.report;

import java.math.BigDecimal;
import java.util.Locale;

import io.quarkus.prreview.classification.api.QuestionResult;

/** How {@code jev}'s answers are written. Formatting only: no answer is compared with any value. */
public final class AnswerFormat {

    private AnswerFormat() {
    }

    /** Two decimals, for the terminal and the fallback summary. Display only. */
    public static String rounded(double probability) {
        return String.format(Locale.ROOT, "%.2f", probability);
    }

    /** As returned, without rounding or scientific notation, for the report. */
    public static String exact(double probability) {
        String text = Double.toString(probability);
        return text.contains("E") ? new BigDecimal(text).stripTrailingZeros().toPlainString() : text;
    }

    /**
     * An answer in short form: {@code 0.88} for yes/no; {@code jev}'s chosen option and its probability, e.g.
     * {@code no-test-changes 0.64}, for a choice (the option alone when {@code jev} reported no probability for it).
     */
    public static String shortAnswer(QuestionResult.Answer answer) {
        return switch (answer) {
            case QuestionResult.YesNo yesNo -> rounded(yesNo.probability());
            case QuestionResult.Choice choice -> {
                Double p = choice.probabilities().get(choice.value());
                yield p == null ? choice.value() : choice.value() + " " + rounded(p);
            }
        };
    }
}
