package io.quarkus.prreview.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for the {@code review-range} spec parser (plan §10). */
class PrRangeSpecTest {

    @Test
    void parsesListsAndRanges() {
        assertEquals(List.of(100, 101, 102, 110, 120, 121), PrRangeSpec.parse("100-102,110,120-121"));
    }

    @Test
    void dedupesAndPreservesFirstSeenOrder() {
        assertEquals(List.of(5, 6, 7), PrRangeSpec.parse("5,6,7,6,5"));
    }

    @Test
    void rejectsDescendingRange() {
        assertThrows(IllegalArgumentException.class, () -> PrRangeSpec.parse("10-5"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> PrRangeSpec.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> PrRangeSpec.parse(""));
        assertThrows(IllegalArgumentException.class, () -> PrRangeSpec.parse("0"));
    }
}
