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
    @Launch("--help")
    void helpExitsZero(LaunchResult result) {
        assertTrue(result.getOutput().contains("review"), result.getOutput());
    }
}
