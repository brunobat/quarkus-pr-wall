package io.quarkus.prreview.pr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.quarkus.prreview.pr.model.ChangedFile;
import io.quarkus.prreview.pr.model.ChangedFile.Kind;

/**
 * Lists the files a unified git diff changes, from its per-file headers. Every file in the diff is listed;
 * nothing is filtered or grouped by path or type.
 */
public final class DiffFiles {

    private static final String DIFF_GIT = "diff --git ";

    private DiffFiles() {
    }

    public static List<ChangedFile> parse(String diff) {
        List<ChangedFile> files = new ArrayList<>();
        Header header = null;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith(DIFF_GIT)) {
                if (header != null) {
                    files.add(header.toChangedFile());
                }
                header = new Header(line.substring(DIFF_GIT.length()));
            } else if (header != null && !header.inHunks) {
                header.read(line);
            }
        }
        if (header != null) {
            files.add(header.toChangedFile());
        }
        return files;
    }

    /** The extended header lines of one file's diff, up to its first hunk. */
    private static final class Header {

        private final String headerOldPath;
        private final String headerNewPath;
        private String oldPath;
        private String newPath;
        private Kind kind = Kind.MODIFIED;
        private boolean binary;
        private boolean inHunks;

        Header(String paths) {
            String[] split = splitHeaderPaths(paths);
            this.headerOldPath = split[0];
            this.headerNewPath = split[1];
        }

        void read(String line) {
            if (line.startsWith("@@")) {
                inHunks = true;
            } else if (line.startsWith("new file mode")) {
                kind = Kind.ADDED;
            } else if (line.startsWith("deleted file mode")) {
                kind = Kind.DELETED;
            } else if (line.startsWith("rename from ")) {
                kind = Kind.RENAMED;
                oldPath = unquote(line.substring("rename from ".length()));
            } else if (line.startsWith("rename to ")) {
                newPath = unquote(line.substring("rename to ".length()));
            } else if (line.startsWith("--- ") && !line.equals("--- /dev/null")) {
                oldPath = oldPath != null ? oldPath : stripPrefix(unquote(line.substring(4)), "a/");
            } else if (line.startsWith("+++ ") && !line.equals("+++ /dev/null")) {
                newPath = newPath != null ? newPath : stripPrefix(unquote(line.substring(4)), "b/");
            } else if (line.startsWith("Binary files ") || line.equals("GIT binary patch")) {
                binary = true;
            }
        }

        ChangedFile toChangedFile() {
            String before = oldPath != null ? oldPath : headerOldPath;
            String after = newPath != null ? newPath : headerNewPath;
            return switch (kind) {
                case DELETED -> new ChangedFile(before, kind, null, binary);
                case RENAMED -> new ChangedFile(after, kind, before, binary);
                default -> new ChangedFile(after, kind, null, binary);
            };
        }
    }

    /**
     * Splits {@code a/<old> b/<new>}, where git quotes each path on its own. Unambiguous when both paths are equal,
     * which covers all but renames; a rename's {@code rename from}/{@code rename to} lines then give the real paths.
     */
    static String[] splitHeaderPaths(String paths) {
        if (paths.startsWith("\"")) {
            int end = closingQuote(paths);
            return new String[] { stripPrefix(unquote(paths.substring(0, end + 1)), "a/"),
                    stripPrefix(unquote(paths.substring(end + 2)), "b/") };
        }
        int length = paths.length();
        if ((length - 5) % 2 == 0 && length > 5) {
            String same = paths.substring(2, 2 + (length - 5) / 2);
            if (paths.equals("a/" + same + " b/" + same)) {
                return new String[] { same, same };
            }
        }
        int quoted = paths.lastIndexOf(" \"b/");
        if (quoted >= 0) {
            return new String[] { stripPrefix(paths.substring(0, quoted), "a/"),
                    stripPrefix(unquote(paths.substring(quoted + 1)), "b/") };
        }
        int split = paths.lastIndexOf(" b/");
        if (split >= 0) {
            return new String[] { stripPrefix(paths.substring(0, split), "a/"), paths.substring(split + 3) };
        }
        // Not a shape git writes; keep the raw text rather than fail the PR.
        return new String[] { paths, paths };
    }

    private static int closingQuote(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                i++;
            } else if (s.charAt(i) == '"') {
                return i;
            }
        }
        return s.length() - 1;
    }

    /**
     * Git quotes paths with special characters as C strings: single-character escapes, and octal {@code \NNN} for
     * each byte of a non-ASCII character. This decodes them back to the UTF-8 path.
     */
    static String unquote(String s) {
        if (s.length() < 2 || !s.startsWith("\"") || !s.endsWith("\"")) {
            return s;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int end = s.length() - 1;
        for (int i = 1; i < end; i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= end) {
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                continue;
            }
            char next = s.charAt(++i);
            if (next >= '0' && next <= '7' && i + 2 < end) {
                bytes.write(Integer.parseInt(s.substring(i, i + 3), 8));
                i += 2;
                continue;
            }
            bytes.write(switch (next) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'a' -> 7;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'v' -> 11;
                default -> next;
            });
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String stripPrefix(String path, String prefix) {
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }
}
