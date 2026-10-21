package io.quarkus.prreview.cli;

import io.quarkus.prreview.report.services.MarkdownRenderer;
import io.quarkus.prreview.report.services.ProseEnricher;
import io.quarkus.prreview.review.services.ReviewEngine;
import io.quarkus.prreview.review.model.ReviewResult;
import jakarta.inject.Inject;
import picocli.CommandLine;

/**
 * {@code review <pr>} — full-depth review of a single targeted PR (plan §10): runs every deep pipeline A–G
 * regardless of the triage gate. Read-only: the agent never posts, approves, or merges — it prints a markdown
 * report to stdout for a human to act on.
 */
@CommandLine.Command(name = "review", description = "Review a single PR at full depth and print a markdown report.")
public class ReviewCommand implements Runnable {

    @CommandLine.Parameters(index = "0", paramLabel = "<pr>", description = "PR number to review.")
    int pr;

    @CommandLine.Option(names = "--no-prose",
            description = "Skip Opus fix-prose generation; render the deterministic summaries only.")
    boolean noProse;

    @Inject
    ReviewEngine reviewEngine;

    @Inject
    ProseEnricher proseEnricher;

    @Inject
    MarkdownRenderer renderer;

    @Override
    public void run() {
        ReviewResult result = reviewEngine.review(pr).await().indefinitely();
        ReviewResult enriched = noProse ? result : proseEnricher.enrich(result);
        System.out.println(renderer.renderPr(enriched));
    }
}
