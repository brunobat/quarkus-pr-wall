package io.quarkus.prreview.classification.github.ci.model;

import java.util.List;

/**
 * A check that did not pass, with its rows: at least one.
 *
 * @param check the check
 * @param rows one per failing test, or one saying what is known instead
 */
public record FailedCheck(CiCheck check, List<FailureRow> rows) {
}
