package io.quarkus.prreview.classification.github.ci.botcomment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkus.prreview.clients.gh.model.GhComment;

/**
 * Reads quarkus-bot's workflow status comments: plain line-by-line string handling of the layout the bot has used
 * since 2024 ({@code # Status for workflow `<workflow>`} and "This is the status report for running `<workflow>` on
 * commit <sha>."), with either {@code :x:} or {@code ✖} before each entry. Anything else, including the 2022 layout
 * and comments by other authors, is not a status comment.
 */
public final class BotCommentParser {

    public static final String BOT = "quarkus-bot";

    /** ISO-8601 creation times compare as text; a missing one is older than any other. */
    private static final Comparator<String> CREATED = Comparator.nullsFirst(Comparator.naturalOrder());
    private static final Pattern HEADING = Pattern.compile("# Status for workflow `([^`]+)`");
    private static final Pattern SENTENCE = Pattern
            .compile("This is the status report for running `([^`]+)` on commit ([0-9a-fA-F]{40})\\.");
    private static final Pattern ENTRY = Pattern.compile("<p>(?::x:|✖)\\s*<code>(.*)");
    private static final Pattern JOB = Pattern.compile("### :gear: (.+?)(?:\\s*<a [^>]*>#</a>)?");
    private static final Pattern HISTORY = Pattern.compile("<a href=\"([^\"]*)\">History</a>");
    private static final Pattern SCAN = Pattern.compile("\\[:mag:]\\(([^)\\s]+)\\)");
    private static final Pattern MODULE = Pattern.compile("#### :package: (.+)");
    private static final Pattern RUN_ID = Pattern.compile("<!-- Build-Reporter/workflow-run-id:([0-9]+) -->");

    private enum Section {
        OTHER,
        FAILING_JOBS,
        FAILURES,
        FLAKY
    }

    private BotCommentParser() {
    }

    /** Whether any comment on the PR is by quarkus-bot, status comment or not. */
    public static boolean hasBotComment(List<GhComment> comments) {
        return comments.stream().anyMatch(c -> BOT.equals(c.authorLogin()));
    }

    /**
     * The status comment for {@code workflow} on commit {@code head}: the one created last when there are several
     * (re-runs on the same commit); one without a creation time counts as the oldest. Comments for other commits are
     * never returned.
     */
    public static Optional<BotStatusComment> forHead(List<GhComment> comments, String workflow, String head) {
        BotStatusComment found = null;
        for (GhComment comment : comments) {
            Optional<BotStatusComment> parsed = parse(comment);
            if (parsed.isPresent() && parsed.get().workflow().equals(workflow)
                    && parsed.get().commit().equalsIgnoreCase(head)
                    && (found == null || CREATED.compare(parsed.get().createdAt(), found.createdAt()) >= 0)) {
                found = parsed.get();
            }
        }
        return Optional.ofNullable(found);
    }

    /** The comment as a status comment, or empty when it is not one by quarkus-bot in the current layout. */
    public static Optional<BotStatusComment> parse(GhComment comment) {
        if (!BOT.equals(comment.authorLogin()) || comment.body() == null) {
            return Optional.empty();
        }
        String[] lines = comment.body().replace("\r", "").split("\n", -1);
        int i = skipBanner(lines, skipBlank(lines, 0));
        Matcher heading = i < lines.length ? HEADING.matcher(lines[i].strip()) : null;
        if (heading == null || !heading.matches()) {
            return Optional.empty();
        }
        i = skipBlank(lines, i + 1);
        Matcher sentence = i < lines.length ? SENTENCE.matcher(lines[i].strip()) : null;
        if (sentence == null || !sentence.matches() || !sentence.group(1).equals(heading.group(1))) {
            return Optional.empty();
        }

        Map<String, List<BotStatusComment.Entry>> failures = new LinkedHashMap<>();
        Map<List<String>, BotStatusComment.FlakyTest> flaky = new LinkedHashMap<>();
        String runId = null;
        String module = "";
        Map<String, String> scanLinks = new LinkedHashMap<>();
        Section section = Section.OTHER;
        String job = null;
        int nameColumn = -1;
        int scanColumn = -1;
        boolean fenced = false;
        for (i = i + 1; i < lines.length; i++) {
            String line = lines[i];
            // The run id marker closes the comment; it is read even after a code block the comment left open.
            Matcher run = RUN_ID.matcher(line.strip());
            if (run.matches()) {
                runId = run.group(1);
                continue;
            }
            if (line.startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            if (line.startsWith("## ")) {
                section = line.startsWith("## Failing Jobs") ? Section.FAILING_JOBS
                        : line.strip().equals("## Failures") ? Section.FAILURES
                                : line.startsWith("## Flaky tests") ? Section.FLAKY : Section.OTHER;
                job = null;
                module = "";
                continue;
            }
            Matcher jobLine = JOB.matcher(line.strip());
            if (line.startsWith("### ") && jobLine.matches()) {
                job = jobLine.group(1).strip();
                module = "";
                continue;
            }
            Matcher moduleLine = MODULE.matcher(line.strip());
            if (line.startsWith("#### ") && moduleLine.matches()) {
                module = moduleLine.group(1).strip();
                continue;
            }
            if (section == Section.FAILING_JOBS && line.startsWith("|")) {
                String[] cells = line.split("\\|", -1);
                if (nameColumn < 0) {
                    for (int c = 0; c < cells.length; c++) {
                        nameColumn = cells[c].strip().equals("Name") ? c : nameColumn;
                        scanColumn = cells[c].strip().equals("Build scan") ? c : scanColumn;
                    }
                } else if (scanColumn >= 0 && cells.length > Math.max(nameColumn, scanColumn)) {
                    Matcher scan = SCAN.matcher(cells[scanColumn]);
                    if (scan.find()) {
                        scanLinks.put(cells[nameColumn].strip(), scan.group(1));
                    }
                }
                continue;
            }
            Matcher entry = ENTRY.matcher(line);
            if (job != null && (section == Section.FAILURES || section == Section.FLAKY) && entry.matches()) {
                StringBuilder html = new StringBuilder(entry.group(1));
                while (!html.toString().contains("</p>") && i + 1 < lines.length) {
                    html.append('\n').append(lines[++i]);
                }
                String code = html.toString();
                int close = code.indexOf("</code>");
                String rest = close < 0 ? "" : code.substring(close);
                code = decode(close < 0 ? code : code.substring(0, close));
                Matcher history = HISTORY.matcher(rest);
                String historyLink = history.find() ? decode(history.group(1)) : null;
                int[] next = { i };
                String error = historyLink == null ? "" : error(lines, next);
                i = next[0];
                if (section == Section.FLAKY) {
                    if (historyLink != null) {
                        String test = testName(code);
                        flaky.putIfAbsent(List.of(job, test),
                                new BotStatusComment.FlakyTest(job, test, module, historyLink));
                    }
                } else {
                    failures.computeIfAbsent(job, j -> new ArrayList<>()).add(historyLink == null
                            ? new BotStatusComment.Entry(null, oneLine(code), null)
                            : new BotStatusComment.Entry(testName(code), error, historyLink));
                }
            }
        }
        return Optional.of(new BotStatusComment(heading.group(1), sentence.group(2), comment.createdAt(), runId,
                failures, List.copyOf(flaky.values()), scanLinks));
    }

    /** A test's name on one line; a class-only entry, which the bot writes with a trailing dot, loses the dot. */
    private static String testName(String code) {
        String name = oneLine(code);
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    /**
     * The error after a test entry ending at {@code at[0]}: the {@code - `message` - `exception`} bullet, with or
     * without leading spaces, or else the first line of the stack trace in the {@code <details>} block. Moves
     * {@code at[0]} past a bullet it reads.
     */
    private static String error(String[] lines, int[] at) {
        int i = skipBlank(lines, at[0] + 1);
        if (i < lines.length && lines[i].stripLeading().startsWith("- `")) {
            StringBuilder bullet = new StringBuilder(lines[i].stripLeading().substring(2));
            while (i + 1 < lines.length && !lines[i + 1].strip().equals("<details>")
                    && !ENTRY.matcher(lines[i + 1]).matches() && !lines[i + 1].startsWith("#")) {
                bullet.append('\n').append(lines[++i]);
            }
            at[0] = i;
            String text = bullet.toString().strip();
            text = text.startsWith("`") ? text.substring(1) : text;
            text = text.endsWith("`") ? text.substring(0, text.length() - 1) : text;
            return oneLine(text.replace("` - `", " - "));
        }
        if (i < lines.length && lines[i].strip().equals("<details>")) {
            int fence = skipBlank(lines, i + 1);
            if (fence < lines.length && lines[fence].startsWith("```")) {
                int first = skipBlank(lines, fence + 1);
                if (first < lines.length && !lines[first].startsWith("```")) {
                    return oneLine(lines[first]);
                }
            }
        }
        return "";
    }

    /** Skips the "outdated" banner the bot puts above a superseded comment: {@code ---}, quoted lines, {@code ---}. */
    private static int skipBanner(String[] lines, int i) {
        if (i < lines.length && lines[i].strip().equals("---")) {
            int end = i + 1;
            while (end < lines.length && !lines[end].strip().equals("---")) {
                end++;
            }
            return end < lines.length ? skipBlank(lines, end + 1) : i;
        }
        return i;
    }

    private static int skipBlank(String[] lines, int i) {
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        return i;
    }

    private static String decode(String html) {
        return html.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }
}
