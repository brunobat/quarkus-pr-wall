package io.quarkus.prreview.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Writes {@code review-<pr>.md}: to a temporary file first, then moved into place, so no partial report is left. */
public final class ReportWriter {

    private ReportWriter() {
    }

    public static Path write(Path dir, int pr, String content) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve("review-" + pr + ".md");
        // Not Files.createTempFile: its owner-only permissions would carry over to the report. A plain new file
        // gets the usual permissions from the umask.
        Path temp = dir.resolve(".review-" + pr + "-" + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        return target;
    }
}
