package io.quarkus.prreview.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Exported but empty environment variables, resolved the way the app sees them: an environment source above
 * {@code application.yml}'s values, with SmallRye's expression interceptor and this one.
 */
class BlankEnvironmentIsUnsetTest {

    private static SmallRyeConfig config(Map<String, String> environment) {
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors() // expands ${ENV_VAR:default}
                .withInterceptors(new BlankEnvironmentIsUnset())
                .withSources(new EnvConfigSource(environment, 300))
                .withSources(new PropertiesConfigSource(Map.of(
                        "quarkus.langchain4j.typesafe.api-key", "${TYPESAFE_API_KEY:not-set}",
                        "quarkus.langchain4j.anthropic.api-key", "${ANTHROPIC_API_KEY:not-set}",
                        "pr-review.jev.project-context", "${JEV_PROJECT_CONTEXT:bundled}"), "application.yml", 250))
                .build();
    }

    private static String value(SmallRyeConfig config, String name) {
        return config.getConfigValue(name).getValue();
    }

    @Test
    void anEmptyShortNameFallsBackToTheDefault() {
        SmallRyeConfig config = config(Map.of("TYPESAFE_API_KEY", "", "JEV_PROJECT_CONTEXT", " "));

        assertEquals(ApiKey.NOT_SET, value(config, "quarkus.langchain4j.typesafe.api-key"));
        assertEquals("bundled", value(config, "pr-review.jev.project-context"));
    }

    @Test
    void anEmptyApiKeyPropertyMeansNoKey() {
        SmallRyeConfig config = config(Map.of(
                "QUARKUS_LANGCHAIN4J_TYPESAFE_API_KEY", "",
                "QUARKUS_LANGCHAIN4J_ANTHROPIC_API_KEY", " "));

        assertEquals(ApiKey.NOT_SET, value(config, "quarkus.langchain4j.typesafe.api-key"));
        assertEquals(ApiKey.NOT_SET, value(config, "quarkus.langchain4j.anthropic.api-key"));
    }

    @Test
    void aSetValueIsPassedThrough() {
        SmallRyeConfig config = config(Map.of(
                "TYPESAFE_API_KEY", "short",
                "QUARKUS_LANGCHAIN4J_ANTHROPIC_API_KEY", "long"));

        assertEquals("short", value(config, "quarkus.langchain4j.typesafe.api-key"));
        assertEquals("long", value(config, "quarkus.langchain4j.anthropic.api-key"));
    }

    /** Unsetting it would hide its default too, so it is left alone: an empty {@code PR_REVIEW_*} is unsupported. */
    @Test
    void anEmptyPropertyFormIsLeftAlone() {
        SmallRyeConfig config = config(Map.of("PR_REVIEW_JEV_PROJECT_CONTEXT", ""));

        assertEquals("", value(config, "pr-review.jev.project-context"));
    }
}
