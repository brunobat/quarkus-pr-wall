package io.quarkus.prreview.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for the {@code review-range} spec parser (plan §10). */
class PrSpecTest {

    @Test
    void parsesListsAndRanges() {
        assertEquals(List.of(100, 101, 102, 110, 120, 121), PrSpec.parse("100-102,110,120-121"));
    }

    @Test
    void dedupesAndPreservesFirstSeenOrder() {
        assertEquals(List.of(5, 6, 7), PrSpec.parse("5,6,7,6,5"));
    }

    @Test
    void rejectsDescendingRange() {
        assertThrows(IllegalArgumentException.class, () -> PrSpec.parse("10-5"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> PrSpec.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> PrSpec.parse(""));
        assertThrows(IllegalArgumentException.class, () -> PrSpec.parse("0"));
    }
}
