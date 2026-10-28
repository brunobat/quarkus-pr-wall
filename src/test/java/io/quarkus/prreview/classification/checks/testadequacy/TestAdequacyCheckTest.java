package io.quarkus.prreview.classification.checks.testadequacy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Pins the question wording to the spec (Clarifications #37 to #39): a change to what {@code jev} is asked must be
 * a deliberate change here too, and the README's question table follows it.
 */
class TestAdequacyCheckTest {

    @Test
    void behaviour() {
        assertEquals("Does this PR change the runtime behaviour of the software?", TestAdequacyCheck.BEHAVIOUR.text());
        assertEquals("it changes code that runs, configuration read at runtime, or dependency versions that are shipped",
                TestAdequacyCheck.BEHAVIOUR.yesWhen());
        assertEquals("it only changes documentation, comments, formatting, tests, or build/CI tooling that does not"
                + " affect the shipped artifact", TestAdequacyCheck.BEHAVIOUR.noWhen());
    }

    @Test
    void testsChanged() {
        assertEquals("Does this PR add or modify tests?", TestAdequacyCheck.TESTS_CHANGED.text());
        assertEquals("it adds or modifies files under a src/test/ directory", TestAdequacyCheck.TESTS_CHANGED.yesWhen());
        assertEquals("it changes no file under a src/test/ directory", TestAdequacyCheck.TESTS_CHANGED.noWhen());
    }

    @Test
    void reach() {
        assertEquals("How much of the changed runtime code do the tests this PR adds or modifies execute?",
                TestAdequacyCheck.REACH.text());
        assertEquals(options(
                "all", "Every changed runtime code path is executed by a changed test.",
                "some", "Some changed runtime code paths are executed by a changed test; others are not.",
                "none", "The PR changes runtime behaviour and adds or modifies tests, but they execute none of the"
                        + " changed runtime code.",
                "no-runtime-change", "The PR changes no runtime behaviour, whether or not it changes tests.",
                "no-test-changes", "The PR changes runtime behaviour but adds or modifies no tests.",
                "undetermined", "The diff does not give enough to tell, e.g. binary or generated changes, behaviour"
                        + " too indirect to trace, or tests that depend on code outside the diff."),
                TestAdequacyCheck.REACH.options());
        assertEquals(List.copyOf(TestAdequacyCheck.REACH.options().keySet()),
                List.of("all", "some", "none", "no-runtime-change", "no-test-changes", "undetermined"));
    }

    @Test
    void asserts() {
        assertEquals("Do the tests this PR adds or modifies assert the outcome of the changed code they execute?",
                TestAdequacyCheck.ASSERTS.text());
        assertEquals(options(
                "all", "Every changed path the changed tests execute has its outcome asserted.",
                "some", "Some executed changed paths are asserted; others are only called, built or smoke-run.",
                "none", "The changed tests execute changed code but assert none of its outcomes.",
                "no-runtime-change", "The PR changes no runtime behaviour, whether or not it changes tests.",
                "no-test-changes", "The PR changes runtime behaviour but adds or modifies no tests.",
                "no-changed-code-reached", "The PR changes runtime behaviour and tests, but the changed tests execute"
                        + " none of the changed runtime code.",
                "undetermined", "The diff does not give enough to tell."),
                TestAdequacyCheck.ASSERTS.options());
        assertEquals(List.copyOf(TestAdequacyCheck.ASSERTS.options().keySet()), List.of("all", "some", "none",
                "no-runtime-change", "no-test-changes", "no-changed-code-reached", "undetermined"));
    }

    private static Map<String, String> options(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
