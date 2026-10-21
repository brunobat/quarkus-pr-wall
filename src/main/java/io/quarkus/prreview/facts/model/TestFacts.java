package io.quarkus.prreview.facts.model;

import java.util.List;

/**
 * Syntactic test facts for one located/added test (design §3.1 {@code map-tests}).
 *
 * <p>Existence alone never clears the §4 zero-tests Blocker: {@code disabled} and {@code hasAssertion}
 * are the two load-bearing facts, and {@code body} is what lets the reasoning layer grade adequacy
 * rather than merely detect presence (plan §5.2). The fact always carries the <em>full</em> body; each
 * state builder decides how much of it to carry (compact at triage, complete at the test-adequacy deep pipeline).
 *
 * @param testPath       path of the test file
 * @param targetRuntime  the runtime file this test is believed to cover (or {@code null})
 * @param added          true if this test is added/modified in the diff; false if it is a
 *                       pre-existing in-tree test (relevant to the covered-by-existing-test class)
 * @param disabled       parses {@code @Disabled}/{@code @Ignore}/{@code @Test(enabled=false)}/skip
 * @param hasAssertion   parses AssertJ {@code assertThat} / JUnit {@code assert*} calls
 * @param body           the full added-line bodies of the relevant test methods
 */
public record TestFacts(
        String testPath,
        String targetRuntime,
        boolean added,
        boolean disabled,
        boolean hasAssertion,
        List<String> body) {

    public TestFacts {
        body = body == null ? List.of() : List.copyOf(body);
    }
}
