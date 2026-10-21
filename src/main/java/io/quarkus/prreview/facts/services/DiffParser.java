package io.quarkus.prreview.facts.services;


import io.quarkus.prreview.facts.model.Hunk;
import io.quarkus.prreview.facts.model.ParsedFile;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a unified {@code git}/{@code gh pr diff} into per-file {@link ParsedFile} records with bounded
 * hunks. Pure and deterministic — the foundation every deterministic fact and every {@code jev} state
 * is built from, so it is unit-tested directly against sample diffs.
 */
@ApplicationScoped
public class DiffParser {

    /** Cap hunk line capture so a huge diff cannot blow the {@code jev} state budget (plan §9). */
    private static final int MAX_LINES_PER_HUNK = 400;

    public List<ParsedFile> parse(String diff) {
        List<ParsedFile> files = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return files;
        }
        String[] lines = diff.split("\n", -1);

        String path = null;
        boolean newFile = false;
        boolean deletedFile = false;
        int added = 0;
        int removed = 0;
        List<Hunk> hunks = new ArrayList<>();

        int hunkStart = 0;
        List<String> hunkAdded = null;
        List<String> hunkRemoved = null;

        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                flush(files, path, hunks, hunkAdded, hunkRemoved, hunkStart, added, removed, newFile, deletedFile);
                path = parseGitPath(line);
                newFile = false;
                deletedFile = false;
                added = 0;
                removed = 0;
                hunks = new ArrayList<>();
                hunkAdded = null;
                hunkRemoved = null;
            } else if (line.startsWith("new file mode")) {
                newFile = true;
            } else if (line.startsWith("deleted file mode")) {
                deletedFile = true;
            } else if (line.startsWith("+++ ")) {
                String p = stripDiffMarker(line.substring(4));
                if (p != null) {
                    path = p;
                }
            } else if (line.startsWith("--- ")) {
                // keep +++ as the authoritative new path
            } else if (line.startsWith("@@")) {
                if (hunkAdded != null) {
                    hunks.add(new Hunk(hunkStart, hunkAdded, hunkRemoved));
                }
                hunkStart = parseNewStart(line);
                hunkAdded = new ArrayList<>();
                hunkRemoved = new ArrayList<>();
            } else if (hunkAdded != null && line.startsWith("+") && !line.startsWith("+++")) {
                added++;
                if (hunkAdded.size() < MAX_LINES_PER_HUNK) {
                    hunkAdded.add(line.substring(1));
                }
            } else if (hunkRemoved != null && line.startsWith("-") && !line.startsWith("---")) {
                removed++;
                if (hunkRemoved.size() < MAX_LINES_PER_HUNK) {
                    hunkRemoved.add(line.substring(1));
                }
            }
        }
        flush(files, path, hunks, hunkAdded, hunkRemoved, hunkStart, added, removed, newFile, deletedFile);
        return files;
    }

    private void flush(List<ParsedFile> files, String path, List<Hunk> hunks,
                       List<String> hunkAdded, List<String> hunkRemoved, int hunkStart,
                       int added, int removed, boolean newFile, boolean deletedFile) {
        if (path == null) {
            return;
        }
        if (hunkAdded != null) {
            hunks.add(new Hunk(hunkStart, hunkAdded, hunkRemoved));
        }
        files.add(new ParsedFile(path, hunks, added, removed, newFile, deletedFile));
    }

    private static String parseGitPath(String line) {
        // diff --git a/path b/path
        int bIdx = line.indexOf(" b/");
        if (bIdx < 0) {
            return null;
        }
        return line.substring(bIdx + 3).trim();
    }

    private static String stripDiffMarker(String s) {
        s = s.trim();
        if (s.equals("/dev/null")) {
            return null;
        }
        if (s.startsWith("a/") || s.startsWith("b/")) {
            return s.substring(2);
        }
        return s;
    }

    private static int parseNewStart(String hunkHeader) {
        // @@ -l,s +l,s @@
        int plus = hunkHeader.indexOf('+');
        if (plus < 0) {
            return 0;
        }
        int end = plus + 1;
        while (end < hunkHeader.length() && (Character.isDigit(hunkHeader.charAt(end)))) {
            end++;
        }
        try {
            return Integer.parseInt(hunkHeader.substring(plus + 1, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
