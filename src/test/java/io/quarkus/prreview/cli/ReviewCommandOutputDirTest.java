package io.quarkus.prreview.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import picocli.CommandLine;

/** Uses the Quarkus picocli factory, so the default comes from the real configuration through CDI. */
@QuarkusTest
class ReviewCommandOutputDirTest {

    @Inject
    CommandLine.IFactory factory;

    @Test
    void defaultsToTheConfiguredDirectory() {
        ReviewCommand command = new ReviewCommand();
        new CommandLine(command, factory).parseArgs("5");
        assertEquals(Path.of("reports"), command.reportDir());
    }

    @Test
    void optionOverridesTheConfiguredDirectory() {
        ReviewCommand command = new ReviewCommand();
        new CommandLine(command, factory).parseArgs("5", "--output-dir", "out/r");
        assertEquals(Path.of("out/r"), command.reportDir());
    }
}
