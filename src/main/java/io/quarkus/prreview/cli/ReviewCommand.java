package io.quarkus.prreview.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.concurrent.Callable;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import io.quarkus.arc.Unremovable;
import io.quarkus.prreview.classification.submission.ProjectContextLoader;
import io.quarkus.prreview.clients.jev.JevClient;
import io.quarkus.prreview.config.ReviewConfig;
import io.quarkus.prreview.report.ReportWriter;
import io.quarkus.prreview.report.markdown.MarkdownReport;
import io.quarkus.prreview.report.terminal.TerminalRenderer;
import io.quarkus.prreview.triage.PrClassifier;
import io.quarkus.prreview.triage.model.PrOutcome;
import picocli.CommandLine;

@CommandLine.Command(name = "review", mixinStandardHelpOptions = true, versionProvider = PrReviewCommand.Version.class,
        defaultValueProvider = ReviewCommand.ConfiguredDefaults.class,
        description = "Classify one or more PRs and write a markdown report for each.")
public class ReviewCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Inject
    PrClassifier classifier;

    @Inject
    JevClient jev;

    @Inject
    ProjectContextLoader projectContext;

    @CommandLine.Parameters(arity = "1..*", paramLabel = "<pr>", description = "PR numbers, processed in the order given.")
    List<Integer> prs;

    @CommandLine.Option(names = "--output-dir", paramLabel = "<dir>",
            description = "Directory for the review-<pr>.md reports. Default: ${DEFAULT-VALUE}.")
    Path outputDir;

    @Override
    public Integer call() {
        SequencedSet<Integer> uniquePrs = uniquePrs();
        Path dir = reportDir();
        if (!jev.keyConfigured()) {
            // A setup error: every PR would fail the same way, so none is processed.
            return setupError("no jev API key; set TYPESAFE_API_KEY");
        }
        try {
            projectContext.get();
        } catch (IllegalArgumentException e) {
            return setupError(e.getMessage());
        }
        PrintWriter out = spec.commandLine().getOut();
        int errors = 0;
        for (int pr : uniquePrs) {
            if (pr != uniquePrs.getFirst()) {
                out.println();
            }
            PrOutcome outcome = classifier.classify(pr);
            ReportWriter.Result report = ReportWriter.write(dir, pr, MarkdownReport.render(outcome));
            // Each block is printed as soon as its PR is done.
            out.print(TerminalRenderer.render(outcome, report));
            out.flush();
            if (!(outcome instanceof PrOutcome.Classified) || !(report instanceof ReportWriter.Written)) {
                errors++;
            }
        }
        return exitCode(uniquePrs.size(), errors);
    }

    /** Prints a one-line {@code error: <message>} and returns exit code 1. */
    private int setupError(String message) {
        spec.commandLine().getErr().println("error: " + message);
        spec.commandLine().getErr().flush();
        return 1;
    }

    /**
     * Clarification #33: no classification affects the exit code ("Let's not fail for now. We are just classifying
     * the PR."). It is 1 only when every PR had an error (not classified, or report not written), otherwise 0.
     */
    static int exitCode(int prs, int errors) {
        return errors == prs ? 1 : 0;
    }

    /** The report directory: {@code --output-dir}, defaulting to {@link ReviewConfig#outputDir()}; empty is a usage error. */
    Path reportDir() {
        if (outputDir.toString().isBlank()) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--output-dir must not be empty");
        }
        return outputDir;
    }

    /** PR numbers in first-seen order, each once; anything not a positive number is a usage error (exit 2). */
    SequencedSet<Integer> uniquePrs() {
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
