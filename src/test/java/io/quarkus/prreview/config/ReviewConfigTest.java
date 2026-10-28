package io.quarkus.prreview.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ReviewConfigTest {

    @Inject
    ReviewConfig config;

    @Test
    void defaultsApplyWhenEnvironmentVariablesAreUnset() {
        assertEquals("quarkusio/quarkus", config.gh().repo());
        assertEquals("gh", config.gh().binary());
        assertEquals(Duration.ofSeconds(60), config.gh().timeout());
        assertEquals(60_000, config.jev().maxSubmissionChars());
        assertEquals("classpath:project-context/quarkusio-quarkus.txt", config.jev().projectContext());
        assertEquals(Path.of("reports"), config.outputDir());
    }
}
