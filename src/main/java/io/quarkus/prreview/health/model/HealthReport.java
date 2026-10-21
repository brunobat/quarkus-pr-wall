package io.quarkus.prreview.health.model;

import java.util.List;

/**
 * The outcome of a {@code pr-review health} run (plan §12 phase 1): every external dependency probed,
 * with required failures separated from optional degradations.
 */
public record HealthReport(List<DependencyHealth> dependencies) {

    public HealthReport {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    /** True when nothing required is broken — an optional dependency being absent is still healthy. */
    public boolean isHealthy() {
        return dependencies.stream().noneMatch(DependencyHealth::isBlocking);
    }

    public boolean isDegraded() {
        return isHealthy() && dependencies.stream().anyMatch(d -> d.status() != DependencyHealth.Status.OK);
    }
}
