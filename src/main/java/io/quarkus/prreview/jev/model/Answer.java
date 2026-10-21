package io.quarkus.prreview.jev.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * One typed answer in a {@code jev} response (plan §2). Fields are populated per {@code type}:
 * <ul>
 *   <li>{@code choice}: {@link #choice}, {@link #probabilities}, {@link #confidence}</li>
 *   <li>{@code score}: {@link #score} (probability-weighted mean), {@link #probabilities},
 *       {@link #confidence}, {@link #legend}</li>
 *   <li>{@code noul}: {@link #noul} only — there is <em>no confidence field</em>; the returned
 *       {@code noul} <em>is</em> the probability of "yes". Confidence is derived in code as
 *       {@code |2·noul − 1|} (plan §2, §6).</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Answer(
        String type,
        String choice,
        Double score,
        Double noul,
        Map<String, Double> probabilities,
        Double confidence,
        Map<String, String> legend) {

    public boolean isNoul() {
        return "noul".equals(type);
    }

    /**
     * Unified confidence in [0,1]: the reported {@code confidence} for choice/score, and the derived
     * {@code |2·noul − 1|} for noul (which has no confidence field). This is the single value the
     * §6 gate bands.
     */
    public double derivedConfidence() {
        if (isNoul()) {
            double p = noul == null ? 0.5 : noul;
            return Math.abs(2.0 * p - 1.0);
        }
        return confidence == null ? 0.0 : confidence;
    }

    /** For a noul answer, the yes-probability itself (plan §2: {@code noul} is the yes value). */
    public double noulValue() {
        return noul == null ? 0.5 : noul;
    }
}
