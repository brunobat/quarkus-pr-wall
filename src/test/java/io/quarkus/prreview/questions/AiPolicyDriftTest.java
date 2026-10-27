package io.quarkus.prreview.questions;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fails when the upstream policy the triage question paraphrases has been edited (design §4.2).
 *
 * <p>{@code content_disproportionate} restates one section of {@code quarkusio/quarkus/AI_POLICY.md} in
 * its own words. Nothing else in the build connects the two: if upstream rewrites that section, the
 * question's criteria keep reading perfectly while no longer describing the policy they cite. This test
 * is that connection, and it is the only one.
 *
 * <p>Deliberately not a {@code @QuarkusTest}: the suite's injected {@code GhClient} is the no-network
 * mock ({@code NoNetworkGuardTest} enforces that), so this shells out to {@code gh} itself. It is the one
 * test in the suite allowed near the network, which is why it is tagged {@code network} and why it
 * <em>skips</em> rather than fails when {@code gh} is missing, unauthenticated, or offline — a drift
 * check that red-lights on a plane is a drift check someone disables.
 *
 * <p>A failure means the pin is stale, not that upstream is wrong. Re-read the section, adjust the
 * question's wording if the policy actually changed, and re-pin.
 */
@Tag("network")
class AiPolicyDriftTest {

    private static final String PIN = "ai-policy-pin.properties";

    @Test
    void theParaphrasedPolicySectionHasNotBeenEdited() {
        Properties pin = pin();
        String repo = pin.getProperty("ai-policy.repo");
        String path = pin.getProperty("ai-policy.path");
        String expected = pin.getProperty("ai-policy.blob-sha");

        String actual = gh("api", "repos/" + repo + "/contents/" + path, "--jq", ".sha");
        assumeTrue(actual != null, "gh is unavailable, unauthenticated or offline — drift unchecked");

        assertEquals(expected, actual, () -> """
                %s/%s has been edited since it was read on %s.

                The '%s' section is paraphrased by the 'content_disproportionate' question in \
                questions/triage.json. Re-read that section, update the question's instructions and \
                criteria if the policy changed, then re-pin %s:
                  ai-policy.blob-sha=%s
                Upstream is authoritative here — this is a prompt to re-read, not a claim that the \
                policy is wrong."""
                .formatted(repo, path, pin.getProperty("ai-policy.read-on"),
                        pin.getProperty("ai-policy.section"), PIN, actual));
    }

    private static Properties pin() {
        Properties p = new Properties();
        try (InputStream in = AiPolicyDriftTest.class.getClassLoader().getResourceAsStream(PIN)) {
            if (in == null) {
                throw new IllegalStateException("missing " + PIN);
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    /** The command's stdout, or null for anything that means "could not ask" rather than "drifted". */
    private static String gh(String... args) {
        List<String> command = new java.util.ArrayList<>(List.of("gh"));
        command.addAll(List.of(args));
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0 || out.isBlank()) {
                return null;
            }
            return out;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
