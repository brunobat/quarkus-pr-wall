package io.quarkus.prreview.interpret.rules.model;

import io.quarkus.prreview.interpret.model.Thresholds;

/**
 * The loaded, validated tuning surface (plan §5.6, §11): the merged tunable {@link Ruleset} plus the
 * named {@link Thresholds}. Both versions are recorded in report provenance so any verdict is bisectable
 * to a specific rule/threshold change.
 */
public record TuningConfig(Ruleset ruleset, Thresholds thresholds) {
}
