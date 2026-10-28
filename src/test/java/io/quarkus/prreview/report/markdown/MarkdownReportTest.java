package io.quarkus.prreview.report.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The escaping helpers on their own; whole reports are checked in {@code RenderingTest}. */
class MarkdownReportTest {

    @Test
    void cellEscapesPipesAndTheBackslashesBeforeThem() {
        assertEquals("a \\| b", MarkdownReport.cell("a | b"));
        // a\|b: the backslash is escaped too, otherwise "\\|" would read as an escaped backslash and a bare pipe.
        assertEquals("a\\\\\\|b", MarkdownReport.cell("a\\|b"));
        assertEquals("a\\\\\\\\\\|b", MarkdownReport.cell("a\\\\|b"));
        // Backslashes not before a pipe are left alone.
        assertEquals("C:\\dir", MarkdownReport.cell("C:\\dir"));
        assertEquals("one line", MarkdownReport.cell("one\n line"));
    }

    @Test
    void inlineTextCannotOpenHtml() {
        assertEquals("&lt;!-- hidden", MarkdownReport.inline("<!-- hidden"));
        assertEquals("&lt;img src=x> &amp;lt; a > b", MarkdownReport.inline("<img src=x> &lt; a > b"));
        assertEquals("&lt;b> \\| x", MarkdownReport.cell("<b> | x"));
        // Code spans are shown literally by GitHub, so they keep the text as is.
        assertEquals("`<!--`", MarkdownReport.code("<!--"));
    }

    @Test
    void codeSpansInsideTextAreLeftAsTyped() {
        assertEquals("Fix `Optional<T>` &amp; `a&b` &lt;b>", MarkdownReport.inline("Fix `Optional<T>` & `a&b` <b>"));
        assertEquals("``x ` <y`` &lt;z", MarkdownReport.inline("``x ` <y`` <z"));
        // An unmatched backtick is literal, so what follows it is still escaped.
        assertEquals("a ` &lt;b", MarkdownReport.inline("a ` <b"));
        assertEquals("`` &lt;b ` c", MarkdownReport.inline("`` <b ` c"));
        // An escaped backtick opens no span; an escaped backslash leaves the backtick after it live.
        assertEquals("\\` &lt;b` c", MarkdownReport.inline("\\` <b` c"));
        assertEquals("\\\\`<b>`", MarkdownReport.inline("\\\\`<b>`"));
        // A backslash before & or < is replaced by the entity, which shows the same character.
        assertEquals("&lt;b> &amp;", MarkdownReport.inline("\\<b> \\&"));
        // Table cells escape pipes inside code spans too.
        assertEquals("`a\\|b` &lt;c", MarkdownReport.cell("`a|b` <c"));
    }

    @Test
    void codeUsesALongerFenceAroundBackticks() {
        assertEquals("`a.java`", MarkdownReport.code("a.java"));
        assertEquals("`` a`b ``", MarkdownReport.code("a`b"));
        // A run of two backticks in the text needs a fence of three, or the text would close the span.
        assertEquals("``` a``<details>b ```", MarkdownReport.code("a``<details>b"));
        assertEquals("```` ```x ````", MarkdownReport.code("```x"));
    }
}
