package io.quarkus.prreview.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import picocli.CommandLine;

@QuarkusTest
class ReviewCommandOutputDirTest {

    @Inject
    ReviewCommand command;

    @Test
    void defaultsToTheConfiguredDirectory() {
        new CommandLine(command).parseArgs("5");
        assertEquals(Path.of("reports"), command.reportDir());
    }

    @Test
    void optionOverridesTheConfiguredDirectory() {
        new CommandLine(command).parseArgs("5", "--output-dir", "out/r");
        assertEquals(Path.of("out/r"), command.reportDir());
    }
}
