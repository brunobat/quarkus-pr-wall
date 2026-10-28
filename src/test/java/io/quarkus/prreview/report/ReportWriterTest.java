package io.quarkus.prreview.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

        Path report = ((ReportWriter.Written) ReportWriter.write(reports, 7, "first")).path();
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

        Path report = ((ReportWriter.Written) ReportWriter.write(dir, 7, "x")).path();

        // Both follow the umask; a temp file's owner-only permissions must not carry over.
        assertEquals(Files.getPosixFilePermissions(plain), Files.getPosixFilePermissions(report));
    }

    @Test
    void aDirectoryUnderARegularFileGivesTheErrorAndNoFile() throws IOException {
        Path file = Files.writeString(dir.resolve("file"), "not a directory");

        ReportWriter.Result result = ReportWriter.write(file.resolve("reports"), 7, "x");

        ReportWriter.Failed failed = assertInstanceOf(ReportWriter.Failed.class, result);
        assertEquals(file.resolve("reports/review-7.md"), failed.target());
        assertTrue(failed.error().startsWith("FileSystemException: "), failed.error());
        try (var files = Files.list(dir)) {
            assertEquals(List.of(file), files.toList());
        }
        assertEquals("not a directory", Files.readString(file));
    }

    @Test
    void aFailedWriteLeavesNoPartialFile() throws IOException {
        // The target is an existing directory, so the move into place fails after the temporary file was written.
        Files.createDirectory(dir.resolve("review-7.md"));
        Files.writeString(dir.resolve("review-7.md/keep"), "x");

        ReportWriter.Result result = ReportWriter.write(dir, 7, "content");

        assertInstanceOf(ReportWriter.Failed.class, result);
        try (var files = Files.list(dir)) {
            assertEquals(List.of(dir.resolve("review-7.md")), files.toList(), "no temporary file is left");
        }
    }
}
