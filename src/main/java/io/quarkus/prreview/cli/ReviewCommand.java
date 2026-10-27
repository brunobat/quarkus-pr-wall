package io.quarkus.prreview.cli;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.concurrent.Callable;

import jakarta.enterprise.context.Dependent;

import io.quarkus.arc.Unremovable;
import io.quarkus.prreview.config.ReviewConfig;
import picocli.CommandLine;

@CommandLine.Command(name = "review", mixinStandardHelpOptions = true, versionProvider = PrReviewCommand.Version.class,
        defaultValueProvider = ReviewCommand.ConfiguredDefaults.class,
        description = "Classify one or more PRs and write a markdown report for each.")
public class ReviewCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Parameters(arity = "1..*", paramLabel = "<pr>", description = "PR numbers, processed in the order given.")
    List<Integer> prs;

    @CommandLine.Option(names = "--output-dir", paramLabel = "<dir>",
            description = "Directory for the review-<pr>.md reports. Default: ${DEFAULT-VALUE}.")
    Path outputDir;

    @Override
    public Integer call() {
        SequencedSet<Integer> uniquePrs = uniquePrs();
        reportDir();
        for (int pr : uniquePrs) {
            spec.commandLine().getOut().println("PR #" + pr);
        }
        spec.commandLine().getOut().flush();
        return 0;
    }

    /** The report directory: {@code --output-dir}, defaulting to {@link ReviewConfig#outputDir()}; empty is a usage error. */
    Path reportDir() {
        if (outputDir.toString().isBlank()) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--output-dir must not be empty");
        }
        return outputDir;
    }

    /** PR numbers in first-seen order, each once; anything not a positive number is a usage error (exit 2). */
    private SequencedSet<Integer> uniquePrs() {
        SequencedSet<Integer> unique = new LinkedHashSet<>();
        for (int pr : prs) {
            if (pr <= 0) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "Invalid PR number: " + pr + " (must be a positive integer)");
            }
            unique.add(pr);
        }
        return unique;
    }

    /** Takes option defaults from {@link ReviewConfig}, so they are declared once and shown in {@code --help}. */
    @Dependent
    @Unremovable
    public static class ConfiguredDefaults implements CommandLine.IDefaultValueProvider {

        private final ReviewConfig config;

        public ConfiguredDefaults(ReviewConfig config) {
            this.config = config;
        }

        @Override
        public String defaultValue(CommandLine.Model.ArgSpec argSpec) {
            if (argSpec.isOption() && "--output-dir".equals(((CommandLine.Model.OptionSpec) argSpec).longestName())) {
                return config.outputDir().toString();
            }
            return null;
        }
    }
}
