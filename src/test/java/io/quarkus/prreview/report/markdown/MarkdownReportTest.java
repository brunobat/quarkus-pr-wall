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
    void codeUsesALongerFenceAroundBackticks() {
        assertEquals("`a.java`", MarkdownReport.code("a.java"));
        assertEquals("`` a`b ``", MarkdownReport.code("a`b"));
    }
}
