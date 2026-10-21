package io.quarkus.prreview.health.model;

/**
 * The state of one external dependency the agent needs (plan §12 phase 1).
 *
 * @param name     {@code gh} · {@code jev} · {@code anthropic}
 * @param status   see {@link Status}
 * @param required true when the agent cannot review at all without this dependency
 * @param detail   human-readable specifics — the failing message, or what was checked
 */
public record DependencyHealth(String name, Status status, boolean required, String detail) {

    public enum Status {
        /** Configured and answering. */
        OK,
        /** No credential/configuration present — a setup gap, not a failure. */
        NOT_CONFIGURED,
        /** Configured but the probe failed. */
        UNREACHABLE
    }

    public static DependencyHealth ok(String name, boolean required, String detail) {
        return new DependencyHealth(name, Status.OK, required, detail);
    }

    public static DependencyHealth notConfigured(String name, boolean required, String detail) {
        return new DependencyHealth(name, Status.NOT_CONFIGURED, required, detail);
    }

    public static DependencyHealth unreachable(String name, boolean required, String detail) {
        return new DependencyHealth(name, Status.UNREACHABLE, required, detail);
    }

    /** A required dependency that is not OK blocks a review; an optional one only degrades it. */
    public boolean isBlocking() {
        return required && status != Status.OK;
    }
}
