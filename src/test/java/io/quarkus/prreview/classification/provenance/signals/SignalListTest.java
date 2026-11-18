package io.quarkus.prreview.classification.provenance.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignalListTest {

    @Test
    void readsAFileSkippingCommentsAndBlankLinesLowerCased(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("signals.txt");
        Files.writeString(file, """
                # a comment
                Co-Authored-By: Claude

                   generated with CLAUDE code  \t
                  # an indented comment
                🤖 Generated with
                """);

        SignalList list = SignalList.load(file.toString(), "PROVENANCE_AI_SIGNALS");

        assertEquals(List.of("co-authored-by: claude", "generated with claude code", "🤖 generated with"),
                list.entries());
        assertEquals(file.toString(), list.source());
    }

    @Test
    void readsABundledList() {
        assertEquals(List.of("dependabot", "quarkus-bot"),
                SignalList.load("classpath:provenance/automation-accounts.txt", "X").entries());
        assertEquals(List.of("dependabot", "quarkus-bot"),
                SignalList.load("classpath:/provenance/automation-accounts.txt", "X").entries());
    }

    @Test
    void aMissingListIsEmptyNotAFailure(@TempDir Path dir) {
        assertEquals(List.of(), SignalList.load(dir.resolve("missing.txt").toString(), "X").entries());
        assertEquals(List.of(), SignalList.load("classpath:provenance/missing.txt", "X").entries());
        assertEquals(List.of(), SignalList.load(dir.toString(), "X").entries());
        assertEquals(List.of(), SignalList.load("bad\0path", "X").entries());
    }
}
