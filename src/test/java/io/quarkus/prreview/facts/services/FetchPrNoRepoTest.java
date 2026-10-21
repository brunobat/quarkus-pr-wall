package io.quarkus.prreview.facts.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An unset {@code GH_REPO} is the likeliest first-run mistake, and the GraphQL fetch cannot paper over it
 * the way {@code -R}-less {@code gh pr view} could — the repository lives inside the query document. It
 * must therefore fail by naming the variable, not by sending a malformed query to GitHub.
 *
 * <p>Deliberately no {@code @TestProfile}: {@code gh.repo} defaults to empty in {@code application.yml},
 * so the default test configuration <em>is</em> the unconfigured case.
 */
@QuarkusTest
class FetchPrNoRepoTest {

    @Inject
    FetchPr fetchPr;

    @Test
    void fetchingWithoutGhRepoNamesTheMissingSetting() {
        GhClient.GhException e = assertThrows(GhClient.GhException.class, () -> fetchPr.fetch(1));

        assertTrue(e.getMessage().contains("GH_REPO"), e.getMessage());
    }
}
