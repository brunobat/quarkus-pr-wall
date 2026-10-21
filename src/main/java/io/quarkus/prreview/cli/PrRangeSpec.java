package io.quarkus.prreview.cli;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses a range spec for {@code review-range} (plan §10): a comma-separated list of PR numbers and/or
 * inclusive {@code a-b} ranges, e.g. {@code "100-105,110,120-122"}. Order is preserved and duplicates are
 * removed; the engine handles the per-PR ranking afterward.
 */
final class PrRangeSpec {

    private PrRangeSpec() {
    }

    static List<Integer> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("empty PR spec");
        }
        Set<Integer> out = new LinkedHashSet<>();
        for (String token : spec.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            int dash = t.indexOf('-');
            if (dash > 0) {
                int lo = parseInt(t.substring(0, dash).trim());
                int hi = parseInt(t.substring(dash + 1).trim());
                if (hi < lo) {
                    throw new IllegalArgumentException("descending range not allowed: " + t);
                }
                for (int i = lo; i <= hi; i++) {
                    out.add(i);
                }
            } else {
                out.add(parseInt(t));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("no PR numbers in spec: " + spec);
        }
        return new ArrayList<>(out);
    }

    private static int parseInt(String s) {
        try {
            int n = Integer.parseInt(s);
            if (n <= 0) {
                throw new IllegalArgumentException("PR number must be positive: " + s);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a PR number: " + s);
        }
    }
}
