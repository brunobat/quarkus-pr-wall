package io.quarkus.prreview.classification.github.ci.model;

/** Whether GitHub marks a check suite on the head commit as awaiting a maintainer's approval to run its workflows. */
public enum Approval {
    /** A suite on the head commit has the conclusion {@code action_required} and no check runs. */
    WAITING,
    /** The head commit's suites were read and none is marked so. */
    NOT_WAITING,
    /** The head commit's suites could not be read. */
    UNAVAILABLE
}
