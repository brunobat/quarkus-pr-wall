package io.quarkus.prreview.classification.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.prreview.classification.model.ProjectContext;

class ProjectContextProducerTest {

    static final String BUNDLED = "classpath:project-context/quarkusio-quarkus.txt";

    @TempDir
    Path dir;

    @Test
    void theBundledContextDescribesQuarkusAndWhereTestsLive() {
        ProjectContext context = ProjectContextProducer.load(BUNDLED);

        assertEquals(BUNDLED, context.source());
        for (String part : new String[] { "Quarkus", "Java", "Maven", "src/test/", "integration-tests/",
                "test-framework/", "bom/application/pom.xml" }) {
            assertTrue(context.text().contains(part), part);
        }
        assertEquals(context.text().strip(), context.text());
    }

    @Test
    void aFilePathIsRead() throws IOException {
        Path file = Files.writeString(dir.resolve("context.txt"), "\n  Project context: another repository.\n");

        ProjectContext context = ProjectContextProducer.load(file.toString());

        assertEquals(new ProjectContext(file.toString(), "Project context: another repository."), context);
    }

    @Test
    void aMissingOrBlankContextIsRejectedNamingTheVariable() throws IOException {
        Path blank = Files.writeString(dir.resolve("blank.txt"), " \n ");
        for (String source : new String[] { dir.resolve("missing.txt").toString(), blank.toString(),
                "classpath:project-context/missing.txt" }) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ProjectContextProducer.load(source));
            assertTrue(e.getMessage().startsWith("JEV_PROJECT_CONTEXT: "), e.getMessage());
            assertTrue(e.getMessage().contains(source.replace("classpath:", "")), e.getMessage());
        }
    }
}
