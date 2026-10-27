package io.quarkus.prreview.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {

    @TempDir
    Path dir;

    @Test
    void writesTheReportAndLeavesNothingElse() throws IOException {
        Path reports = dir.resolve("a/b");

        Path report = ReportWriter.write(reports, 7, "first");
        ReportWriter.write(reports, 7, "second");

        assertEquals(reports.resolve("review-7.md"), report);
        assertEquals("second", Files.readString(report));
        try (var files = Files.list(reports)) {
            assertEquals(List.of(report), files.toList());
        }
    }

    @Test
    void reportGetsTheSamePermissionsAsAnyNewFile() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path plain = Files.createFile(dir.resolve("plain.txt"));

        Path report = ReportWriter.write(dir, 7, "x");

        // Both follow the umask; a temp file's owner-only permissions must not carry over.
        assertEquals(Files.getPosixFilePermissions(plain), Files.getPosixFilePermissions(report));
    }
}
