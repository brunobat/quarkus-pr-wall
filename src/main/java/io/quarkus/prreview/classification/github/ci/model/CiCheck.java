package io.quarkus.prreview.classification.github.ci.model;

/**
 * One check on a PR's head commit.
 *
 * @param kind a check run or a commit status
 * @param name the check run's name, or the commit status's context
 * @param workflow the check run's workflow name; empty for a commit status or a check run not made by a workflow
 * @param value GitHub's value as returned: a check run's conclusion, or its status while it has none; a commit
 *        status's state
 * @param link the check's details or target URL as returned; may be empty
 */
public record CiCheck(Kind kind, String name, String workflow, String value, String link) {

    public enum Kind {
        CHECK_RUN,
        STATUS
    }
}
