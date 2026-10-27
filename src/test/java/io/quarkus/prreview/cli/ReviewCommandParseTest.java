package io.quarkus.prreview.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import picocli.CommandLine;

/**
 * Parses the command with the Quarkus picocli factory, so defaults come from the real configuration through CDI,
 * and checks what {@code call()} will work on, without running it.
 */
@QuarkusTest
class ReviewCommandParseTest {

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

    @Test
    void duplicatePrsAreKeptOnceInFirstSeenOrder() {
        ReviewCommand command = new ReviewCommand();
        new CommandLine(command, factory).parseArgs("5", "7", "5", "3", "7");
        assertEquals(List.of(5, 7, 3), List.copyOf(command.uniquePrs()));
    }
}
