package io.quarkus.prreview.evals;

import io.quarkus.prreview.jev.services.FixtureJevService;
import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;
import java.util.Set;

/**
 * Test profile for the eval suite: swaps {@code jev} for the fixture service and pins the handful of
 * config values the facts layer branches on, so a case's outcome depends only on its own fixture.
 *
 * <p>{@code gh.repo} must be non-blank or {@code AuthorHistoryService}/{@code CommitterStatusService}
 * short-circuit before ever calling {@code gh} — the eval corpus needs those trust facts to actually
 * resolve, since the committer asymmetry is what decides Blocker vs. Needs-attention (§4).
 */
public class EvalProfile implements QuarkusTestProfile {

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(FixtureJevService.class);
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "gh.repo", "quarkusio/quarkus",
                "committers.file", "COMMITTERS.adoc",
                "anthropic.api-key", "");
    }

    @Override
    public String getConfigProfile() {
        return "evals";
    }
}
