package io.quarkus.prreview.interpret.rules.model;

import java.util.List;

/**
 * The aggregated tunable ruleset (plan §5.6). {@code version} is a composite of the per-concern file
 * versions, recorded in report provenance (plan §11) so any verdict is bisectable to a specific rule
 * change. The invariant tier (I-1…I-6) is <em>not</em> here — it is compiled in and non-overridable.
 */
public record Ruleset(String version, List<Rule> rules) {

    public Ruleset {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public List<Rule> enabled() {
        return rules.stream().filter(Rule::isEnabled).toList();
    }
}
