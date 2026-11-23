package io.quarkus.prreview.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlainTextTest {

    @Test
    void whitespaceRunsBecomeOneSpaceAndTheEndsAreStripped() {
        assertEquals("HTTP 502: Bad Gateway", PlainText.oneLine("  HTTP 502:\n\tBad   Gateway\r\n"));
    }

    @Test
    void controlCharactersAreDropped() {
        assertEquals("[31mred[0mtext", PlainText.oneLine("\u001b[31mred\u001b[0m\u202Etext"));
    }

    @Test
    void nullIsEmpty() {
        assertEquals("", PlainText.oneLine(null));
    }
}
