package io.quarkus.prreview.interpret.model;

import java.util.Map;

/**
 * The named confidence/scoring dials (plan §6, §5.6, §11). Every number lives here, referenced by name
 * from rules — never inlined — so re-tuning is a one-file, reviewable, git-bisectable diff. Loaded from
 * {@code thresholds.yaml}; {@link #defaults()} is the starting configuration implied by the §6 bands
 * and the Noul {@code |2p−1|} mapping.
 *
 * @param version        thresholds version, recorded in report provenance
 * @param confirmBand    choice/score confidence ≥ this ⇒ CONFIRM
 * @param flagBand       choice/score confidence in [flagBand, confirmBand) ⇒ FLAG
 * @param noulConfirmYes noul ≥ this ⇒ CONFIRM "yes"
 * @param noulConfirmNo  noul ≤ this ⇒ CONFIRM "no"
 * @param noulFlagYesLow noul in [this, noulConfirmYes) ⇒ FLAG "yes"
 * @param noulFlagNoHigh noul in (noulConfirmNo, this] ⇒ FLAG "no"
 * @param named          additional named integer dials referenced by rules (e.g. adequateScore=3,
 *                       highExposure=3, semanticSizeFloor=2)
 */
public record Thresholds(
        String version,
        double confirmBand,
        double flagBand,
        double noulConfirmYes,
        double noulConfirmNo,
        double noulFlagYesLow,
        double noulFlagNoHigh,
        Map<String, Integer> named) {

    public Thresholds {
        named = named == null ? Map.of() : Map.copyOf(named);
    }

    public int named(String key, int fallback) {
        return named.getOrDefault(key, fallback);
    }

    public static Thresholds defaults() {
        return new Thresholds(
                "thresholds-default",
                0.90, 0.50,
                0.95, 0.05, 0.75, 0.25,
                Map.of(
                        "adequateScore", 3,
                        "highExposure", 3,
                        "blastRadiusFloor", 2,
                        "priorityFloor", 2));
    }
}
