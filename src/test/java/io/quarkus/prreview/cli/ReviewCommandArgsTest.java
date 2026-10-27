package io.quarkus.prreview.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;

@QuarkusMainTest
class ReviewCommandArgsTest {

    @Test
    @Launch(value = "review", exitCode = 2)
    void missingPrIsAUsageError(LaunchResult result) {
        assertTrue(result.getErrorOutput().contains("<pr>"), result.getErrorOutput());
    }

    @Test
    @Launch(value = { "review", "abc" }, exitCode = 2)
    void nonNumericPrIsAUsageError(LaunchResult result) {
        assertTrue(result.getErrorOutput().contains("abc"), result.getErrorOutput());
    }

    @Test
    @Launch(value = { "review", "0" }, exitCode = 2)
    void nonPositivePrIsAUsageError(LaunchResult result) {
        assertTrue(result.getErrorOutput().contains("Invalid PR number: 0"), result.getErrorOutput());
    }

    @Test
    @Launch(value = { "review", "1", "--bogus" }, exitCode = 2)
    void unknownOptionIsAUsageError(LaunchResult result) {
        assertTrue(result.getErrorOutput().contains("--bogus"), result.getErrorOutput());
    }

    @Test
    @Launch({ "review", "5", "5" })
    void duplicatePrIsProcessedOnce(LaunchResult result) {
        assertEquals(1, result.getOutputStream().stream().filter(line -> line.equals("PR #5")).count(),
                result.getOutput());
    }

    @Test
    @Launch(value = { "review", "1", "--output-dir", "" }, exitCode = 2)
    void emptyOutputDirIsAUsageError(LaunchResult result) {
        assertTrue(result.getErrorOutput().contains("--output-dir must not be empty"), result.getErrorOutput());
    }

    @Test
    @Launch({ "review", "--help" })
    void helpShowsTheConfiguredOutputDir(LaunchResult result) {
        // picocli wraps long help lines, so compare with whitespace collapsed
        assertTrue(result.getOutput().replaceAll("\\s+", " ").contains("Default: reports."), result.getOutput());
    }

    @Test
    @Launch({ "review", "-V" })
    void subcommandPrintsTheVersion(LaunchResult result) {
        assertTrue(result.getOutput().contains("pr-review 1.0.0-SNAPSHOT"), result.getOutput());
    }

    @Test
    @Launch("--help")
    void helpExitsZero(LaunchResult result) {
        assertTrue(result.getOutput().contains("review"), result.getOutput());
    }
}
