package io.quarkus.prreview.cli;

import io.quarkus.prreview.health.model.DependencyHealth;
import io.quarkus.prreview.health.model.HealthReport;
import io.quarkus.prreview.health.services.HealthService;
import jakarta.inject.Inject;
import picocli.CommandLine;

/**
 * {@code health} — probe {@code gh}, {@code jev} and Opus before trusting a review run (plan §12 phase 1).
 * Exits non-zero when a <em>required</em> dependency is broken, so it works as a CI/cron preflight; a
 * missing Opus key is reported but exits zero, because the report layer degrades rather than fails.
 */
@CommandLine.Command(name = "health",
        description = "Check gh, jev and Opus connectivity; exit non-zero if a required dependency is broken.")
public class HealthCommand implements Runnable {

    @Inject
    HealthService health;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        HealthReport report = health.check();
        StringBuilder sb = new StringBuilder("# Dependency health\n\n");
        sb.append("| dependency | required | status | detail |\n|---|---|---|---|\n");
        for (DependencyHealth d : report.dependencies()) {
            sb.append("| ").append(d.name())
                    .append(" | ").append(d.required() ? "yes" : "no")
                    .append(" | ").append(d.status())
                    .append(" | ").append(d.detail())
                    .append(" |\n");
        }
        sb.append('\n').append(summary(report)).append('\n');
        System.out.println(sb);

        if (!report.isHealthy()) {
            throw new CommandLine.ExecutionException(spec.commandLine(),
                    "A required dependency is unavailable — see the table above.");
        }
    }

    private static String summary(HealthReport report) {
        if (!report.isHealthy()) {
            return "**Not ready.** A required dependency (gh / jev) is missing or unreachable.";
        }
        if (report.isDegraded()) {
            return "**Ready (degraded).** Every required dependency is up; an optional one is not.";
        }
        return "**Ready.** Every dependency is configured and answering.";
    }
}
