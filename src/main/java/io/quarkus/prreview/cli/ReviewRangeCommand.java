package io.quarkus.prreview.cli;

import io.quarkus.prreview.report.services.MarkdownRenderer;
import io.quarkus.prreview.review.services.RangeEngine;
import io.quarkus.prreview.review.model.RangeReport;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.List;

/**
 * {@code review-range <spec>} — review a set of PRs and print one ranked table (plan §10). The triage gate
 * prunes deep pipelines per PR; every PR still appears in the output, including a dedicated section for PRs
 * that could not be reviewed automatically (design §7/§9). Read-only, like {@code review}.
 *
 * <p>Range mode does not enrich with Opus prose by default — a sweep is a triage view; drill into a single PR
 * with {@code review <pr>} for the fix-prose detail.
 */
@CommandLine.Command(name = "review-range",
        description = "Review a set of PRs (e.g. 100-105,110) and print a ranked table.")
public class ReviewRangeCommand implements Runnable {

    @CommandLine.Parameters(index = "0", paramLabel = "<spec>",
            description = "PR numbers and/or inclusive ranges, e.g. 100-105,110,120-122.")
    String spec;

    @Inject
    RangeEngine rangeEngine;

    @Inject
    MarkdownRenderer renderer;

    @Override
    public void run() {
        List<Integer> prs = PrSpec.parse(spec);
        RangeReport report = rangeEngine.reviewRange(prs).await().indefinitely();
        System.out.println(renderer.renderRange(report));
    }
}
