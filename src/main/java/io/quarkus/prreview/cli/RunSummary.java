package io.quarkus.prreview.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two lines after the last block: how many numbers were given and how long the run took, how each ended, and
 * where the reports go. Counts only; nothing is concluded from them.
 *
 * @param numbers the unique numbers given
 * @param reviewed classified, with the report written
 * @param notPullRequests numbers GitHub says are not pull requests
 * @param failed every other number
 * @param drafts the PRs, reviewed or failed, that are drafts
 * @param waiting the PRs, reviewed or failed, whose workflows wait for approval
 */
record RunSummary(int numbers, int reviewed, int notPullRequests, int failed, int drafts, int waiting) {

    /** {@code Done: …} and {@code Reports: <dir>}, each ending in a newline. */
    String render(Duration took, Path reportDir) {
        List<String> extra = new ArrayList<>();
        if (drafts > 0) {
            extra.add(drafts + (drafts == 1 ? " draft" : " drafts"));
        }
        if (waiting > 0) {
            extra.add(waiting + " waiting for approval");
        }
        return "Done: " + numbers + (numbers == 1 ? " number" : " numbers") + " in " + duration(took) + " — "
                + reviewed + " reviewed, " + notPullRequests + (notPullRequests == 1 ? " not a PR" : " not PRs") + ", "
                + failed + " failed" + (extra.isEmpty() ? "" : " (" + String.join(", ", extra) + ")") + "\n"
                + "Reports: " + reportDir + "\n";
    }

    /** {@code 8.4s} under a minute (tenths, rounded down), {@code 4m 12s}, {@code 1h 3m 20s} (seconds rounded down). */
    static String duration(Duration took) {
        long millis = Math.max(0, took.toMillis());
        if (millis < 60_000) {
            return String.format(Locale.ROOT, "%d.%ds", millis / 1000, millis % 1000 / 100);
        }
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        return (hours > 0 ? hours + "h " : "") + minutes + "m " + seconds % 60 + "s";
    }
}
