package io.quarkus.prreview.cli;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

/**
 * The CLI entry point (plan §10): a read-only PR review agent that never posts, approves, or merges. Its only
 * subcommands analyze and report — {@code review} for a single PR, {@code review-range} for a sweep, and
 * {@code health} to probe the external dependencies before trusting either.
 */
@TopCommand
@CommandLine.Command(
        name = "pr-review",
        mixinStandardHelpOptions = true,
        subcommands = {ReviewCommand.class, ReviewRangeCommand.class, HealthCommand.class},
        description = "Read-only Quarkus PR review agent (jev + Opus). Analyzes and reports; never writes.")
public class PrReviewCli {
}
