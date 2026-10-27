package io.quarkus.prreview.cli;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.concurrent.Callable;

import picocli.CommandLine;

@CommandLine.Command(name = "review", mixinStandardHelpOptions = true,
        description = "Classify one or more PRs and write a markdown report for each.")
public class ReviewCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Parameters(arity = "1..*", paramLabel = "<pr>", description = "PR numbers, processed in the order given.")
    List<Integer> prs;

    @CommandLine.Option(names = "--output-dir", paramLabel = "<dir>",
            description = "Directory for the review-<pr>.md reports (default: pr-review.output-dir).")
    Path outputDir;

    @Override
    public Integer call() {
        SequencedSet<Integer> uniquePrs = uniquePrs();
        for (int pr : uniquePrs) {
            spec.commandLine().getOut().println("PR #" + pr);
        }
        spec.commandLine().getOut().flush();
        return 0;
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
}
