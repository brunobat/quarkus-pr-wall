package io.quarkus.prreview.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                        "JEV_MAX_SUBMISSION_CHARS", "1234"), "env", 300))
                .build();

        ReviewConfig mapped = config.getConfigMapping(ReviewConfig.class);

        assertEquals("octo/repo", mapped.gh().repo());
        assertEquals("/opt/gh", mapped.gh().binary());
        assertEquals(Duration.ofSeconds(5), mapped.gh().timeout());
        assertEquals(1234, mapped.jev().maxSubmissionChars());
    }
}
