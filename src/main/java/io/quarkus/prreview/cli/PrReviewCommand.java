package io.quarkus.prreview.cli;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(name = "pr-review", mixinStandardHelpOptions = true, versionProvider = PrReviewCommand.Version.class,
        description = "Triage-stage classification of quarkusio/quarkus pull requests.", subcommands = ReviewCommand.class)
public class PrReviewCommand {

    static class Version implements CommandLine.IVersionProvider {

        @Override
        public String[] getVersion() {
            return new String[] {
                    "pr-review " + ConfigProvider.getConfig().getOptionalValue("quarkus.application.version", String.class)
                            .orElse("unknown") };
        }
    }
}
