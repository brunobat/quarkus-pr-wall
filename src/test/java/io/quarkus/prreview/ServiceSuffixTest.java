package io.quarkus.prreview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every {@code @ApplicationScoped} class outside {@code clients/} is named {@code ...Service}, so a bean's name says
 * it is one. Clients keep the name of the system they talk to.
 */
class ServiceSuffixTest {

    static final Path MAIN = Path.of("src/main/java");
    static final Path CLIENTS = MAIN.resolve("io/quarkus/prreview/clients");

    @Test
    void everyApplicationScopedClassOutsideClientsEndsInService() throws IOException {
        List<String> beans;
        try (Stream<Path> files = Files.walk(MAIN)) {
            beans = files.filter(f -> f.toString().endsWith(".java")).filter(f -> !f.startsWith(CLIENTS))
                    .filter(ServiceSuffixTest::applicationScoped)
                    .map(f -> f.getFileName().toString().replace(".java", "")).sorted().toList();
        }

        assertEquals(List.of(), beans.stream().filter(n -> !n.endsWith("Service")).toList());
        assertEquals(List.of("AiService", "CiService", "GitHubFactsService", "LoginMemoService", "PrClassifierService",
                "ProjectContextService", "ProvenanceService", "SizeService", "SummaryService", "TestAdequacyService",
                "WhoService"), beans);
    }

    private static boolean applicationScoped(Path file) {
        try {
            return Files.readString(file).lines().anyMatch(l -> l.strip().equals("@ApplicationScoped"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
