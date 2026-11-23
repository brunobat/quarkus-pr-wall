package io.quarkus.prreview.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Checks that each documented environment variable reaches its setting. The variables are supplied as a
 * config source, which is how SmallRye resolves the {@code ${ENV_VAR:default}} expressions in
 * {@link ReviewConfig}.
 */
class ReviewConfigEnvironmentTest {

    @Test
    void environmentVariablesOverrideTheDefaults() {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors() // expands ${ENV_VAR:default}
                .withConverter(Duration.class, 100, new DurationConverter()) // "5s", as Quarkus parses it
                .withMapping(ReviewConfig.class)
                .withSources(new PropertiesConfigSource(Map.of(
                        "GH_REPO", "octo/repo",
                        "GH_BINARY", "/opt/gh",
                        "GH_TIMEOUT", "5s",
                        "GH_CACHE_TTL", "10m",
                        "PROVENANCE_MAX_PEOPLE", "3",
                        "PROVENANCE_AI_SIGNALS", "/etc/signals.txt",
                        "JEV_MAX_SUBMISSION_TOKENS", "1234"), "env", 300))
                .build();

        ReviewConfig mapped = config.getConfigMapping(ReviewConfig.class);

        assertEquals("octo/repo", mapped.gh().repo());
        assertEquals("/opt/gh", mapped.gh().binary());
        assertEquals(Duration.ofSeconds(5), mapped.gh().timeout());
        assertEquals(Duration.ofMinutes(10), mapped.gh().cacheTtl());
        assertEquals(3, mapped.provenance().maxPeople());
        assertEquals("/etc/signals.txt", mapped.provenance().aiSignals());
        // Without the variable, the default points into resource-files/.
        assertEquals("resource-files/automation-accounts.txt", mapped.provenance().automationAccounts());
        assertEquals(1234, mapped.jev().maxSubmissionTokens());
    }

    /** The character limit was replaced by a token limit, with no alias: its old variable is ignored. */
    @Test
    void theOldCharacterLimitVariableMapsToNothing() {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withConverter(Duration.class, 100, new DurationConverter())
                .withMapping(ReviewConfig.class)
                .withSources(new PropertiesConfigSource(Map.of("JEV_MAX_SUBMISSION_CHARS", "1234"), "env", 300))
                .build();

        assertEquals(60_000, config.getConfigMapping(ReviewConfig.class).jev().maxSubmissionTokens());
    }

    /** Tests assert the default, so a developer's own token limit must not reach them. */
    @Test
    void theBuildKeepsTheTokenLimitOutOfTests() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertTrue(pom.contains("<excludedEnvironmentVariable>JEV_MAX_SUBMISSION_TOKENS</excludedEnvironmentVariable>"));
        assertTrue(pom.contains(
                "<excludedEnvironmentVariable>PR_REVIEW_JEV_MAX_SUBMISSION_TOKENS</excludedEnvironmentVariable>"));
        assertFalse(pom.contains("JEV_MAX_SUBMISSION_CHARS"));
    }
}
