package io.quarkus.prreview.report;

import java.util.regex.Pattern;

/** Text from GitHub, jev or Claude made safe to show on one line in the terminal or the report. */
public final class PlainText {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** C0 and C1 control characters, and the bidi controls that could reorder what is shown. */
    private static final Pattern CONTROLS = Pattern
            .compile("[\\p{Cc}\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

    private PlainText() {
    }

    /**
     * {@code text} on one line: whitespace runs become one space, then control characters are dropped, so an escape
     * sequence cannot move the cursor, clear lines or recolour the terminal. {@code null} is empty.
     */
    public static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return CONTROLS.matcher(WHITESPACE.matcher(text.strip()).replaceAll(" ")).replaceAll("").strip();
    }
}
