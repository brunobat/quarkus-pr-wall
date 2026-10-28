package io.quarkus.prreview.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Writes {@code review-<pr>.md}: to a temporary file first, then moved into place, so no partial report is left. A
 * failure is returned, not thrown, so the run can note it and go on.
 */
public final class ReportWriter {

    /** Where the report went, or why it could not be written. */
    public sealed interface Result permits Written, Failed {
    }

    public record Written(Path path) implements Result {
    }

    /** @param error the write error, e.g. {@code FileSystemException: /x/review-1.md: Not a directory} */
    public record Failed(Path target, String error) implements Result {
    }

    private ReportWriter() {
    }

    public static Result write(Path dir, int pr, String content) {
        Path target = dir.resolve("review-" + pr + ".md");
        // Not Files.createTempFile: its owner-only permissions would carry over to the report. A plain new file
        // gets the usual permissions from the umask.
        Path temp = dir.resolve(".review-" + pr + "-" + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.writeString(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return new Written(target);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // The write error is the one worth reporting.
            }
            return new Failed(target, e.getMessage() == null ? e.getClass().getName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
