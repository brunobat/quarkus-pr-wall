package io.quarkus.prreview.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOrResourceTest {

    static final String BUNDLED = "provenance/automation-accounts.txt";

    @TempDir
    Path dir;

    @Test
    void aFileIsReadAsUtf8() throws IOException {
        Path file = Files.writeString(dir.resolve("list.txt"), "café\n");

        assertEquals("café\n", FileOrResource.read(file.toString()));
    }

    @Test
    void aBundledFileIsReadWithOrWithoutALeadingSlash() throws IOException {
        String text = FileOrResource.read("classpath:" + BUNDLED);

        assertTrue(text != null && !text.isBlank());
        assertEquals(text, FileOrResource.read("classpath:/" + BUNDLED));
    }

    @Test
    void aMissingBundledFileIsNull() throws IOException {
        assertNull(FileOrResource.read("classpath:no/such/file.txt"));
    }

    @Test
    void aMissingFileThrows() {
        assertThrows(IOException.class, () -> FileOrResource.read(dir.resolve("missing.txt").toString()));
    }
}
