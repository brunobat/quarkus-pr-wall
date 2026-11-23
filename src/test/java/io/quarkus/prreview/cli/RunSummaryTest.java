package io.quarkus.prreview.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class RunSummaryTest {

    @Test
    void durationsAreShortUnderAMinuteAndInWholeSecondsFromThere() {
        assertEquals("8.4s", RunSummary.duration(Duration.ofMillis(8_430)));
        assertEquals("0.0s", RunSummary.duration(Duration.ofMillis(40)));
        assertEquals("59.9s", RunSummary.duration(Duration.ofMillis(59_999)));
        assertEquals("1m 0s", RunSummary.duration(Duration.ofSeconds(60)));
        assertEquals("4m 12s", RunSummary.duration(Duration.ofSeconds(252)));
        assertEquals("4m 12s", RunSummary.duration(Duration.ofMillis(252_900)));
        assertEquals("1h 3m 20s", RunSummary.duration(Duration.ofSeconds(3_800)));
        assertEquals("1h 0m 0s", RunSummary.duration(Duration.ofHours(1)));
    }

    @Test
    void countsUseSingularFormsAndLeaveOutZeroExtras() {
        Duration took = Duration.ofSeconds(252);
        Path reports = Path.of("reports");

        assertEquals("Done: 23 numbers in 4m 12s — 18 reviewed, 3 not PRs, 2 failed (1 draft, 1 waiting for approval)\n"
                + "Reports: reports\n", new RunSummary(23, 18, 3, 2, 1, 1).render(took, reports));
        assertEquals("Done: 3 numbers in 4m 12s — 1 reviewed, 1 not a PR, 1 failed\nReports: reports\n",
                new RunSummary(3, 1, 1, 1, 0, 0).render(took, reports));
        assertEquals("Done: 1 number in 4m 12s — 0 reviewed, 0 not PRs, 1 failed (2 drafts)\nReports: out/x\n",
                new RunSummary(1, 0, 0, 1, 2, 0).render(took, Path.of("out/x")));
        assertEquals("Done: 2 numbers in 4m 12s — 2 reviewed, 0 not PRs, 0 failed (2 waiting for approval)\n"
                + "Reports: reports\n", new RunSummary(2, 2, 0, 0, 0, 2).render(took, reports));
    }
}
