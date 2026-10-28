package io.quarkus.prreview.classification.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import io.quarkus.prreview.classification.api.CheckQuestions;
import io.quarkus.prreview.classification.api.ClassificationCheck;
import io.quarkus.prreview.classification.checks.testadequacy.TestAdequacyCheck;
import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.ProjectContext;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.pr.DiffFiles;
import io.quarkus.prreview.pr.model.PrContext;
import io.quarkus.prreview.pr.model.PullRequest;

class JevSubmissionTest {

    static final ProjectContext CONTEXT = new ProjectContext("test", "Project context: a test repository.");

    static final String MIXED_DIFF = """
            diff --git a/core/runtime/src/main/java/io/quarkus/Foo.java b/core/runtime/src/main/java/io/quarkus/Foo.java
            index 1111111..2222222 100644
            --- a/core/runtime/src/main/java/io/quarkus/Foo.java
            +++ b/core/runtime/src/main/java/io/quarkus/Foo.java
            @@ -1 +1 @@
            -int x = 1;
            +int x = 2;
            diff --git a/core/runtime/src/test/java/io/quarkus/FooTest.java b/core/runtime/src/test/java/io/quarkus/FooTest.java
            index 3333333..4444444 100644
            --- a/core/runtime/src/test/java/io/quarkus/FooTest.java
            +++ b/core/runtime/src/test/java/io/quarkus/FooTest.java
            @@ -1 +1 @@
            -assertEquals(1, x);
            +assertEquals(2, x);
            """;

    static final String TESTS_ONLY_DIFF = """
            diff --git a/core/runtime/src/test/java/io/quarkus/FooTest.java b/core/runtime/src/test/java/io/quarkus/FooTest.java
            index 3333333..4444444 100644
            --- a/core/runtime/src/test/java/io/quarkus/FooTest.java
            +++ b/core/runtime/src/test/java/io/quarkus/FooTest.java
            @@ -1 +1 @@
            -assertEquals(1, x);
            +assertEquals(2, x);
            """;

    static final String DOCS_ONLY_DIFF = """
            diff --git a/docs/src/main/asciidoc/arc.adoc b/docs/src/main/asciidoc/arc.adoc
            index 9999999..aaaaaaa 100644
            --- a/docs/src/main/asciidoc/arc.adoc
            +++ b/docs/src/main/asciidoc/arc.adoc
            @@ -1 +1 @@
            -= ArC
            += ArC guide
            """;

    static PrContext pr(String diff) {
        return new PrContext(new PullRequest(7, "Secret title", "https://example/pull/7", "OPEN", diff),
                DiffFiles.parse(diff));
    }

    @Test
    void inputHasEveryFileOnceInTheListAndTheFullDiffButNoTitle() {
        String input = JevSubmission.of(pr(MIXED_DIFF), CONTEXT, List.of(new TestAdequacyCheck())).input();

        assertTrue(input.startsWith("Project context: a test repository.\n\nChanged files:\n"), input);
        assertTrue(input.contains("- core/runtime/src/main/java/io/quarkus/Foo.java (modified)\n"), input);
        assertTrue(input.contains("- core/runtime/src/test/java/io/quarkus/FooTest.java (modified)\n"), input);
        assertTrue(input.endsWith("Unified diff:\n" + MIXED_DIFF), input);
        // The test file appears once in the list and 4 times in its own diff headers (diff --git a/ b/, ---, +++),
        // never in a separate tests section.
        assertEquals(1 + 4, count(input, "core/runtime/src/test/java/io/quarkus/FooTest.java"), input);
        assertFalse(input.contains("Secret title"), input);
    }

    @Test
    void sameFourQuestionsWhateverTheDiffContains() {
        for (String diff : List.of(MIXED_DIFF, TESTS_ONLY_DIFF, DOCS_ONLY_DIFF)) {
            DecisionRequest request = JevSubmission.of(pr(diff), CONTEXT, List.of(new TestAdequacyCheck())).toDecisionRequest();
            assertEquals(List.of("test-adequacy.behaviour", "test-adequacy.tests-changed", "test-adequacy.reach",
                    "test-adequacy.assert"), List.copyOf(request.questions().keySet()));
            assertEquals(request.input(), JevSubmission.input(pr(diff), CONTEXT));
        }
    }

    @Test
    void duplicateQuestionIdIsRejected() {
        ClassificationCheck twice = new FixedCheck("dup", "q", "q");
        assertThrows(IllegalStateException.class, () -> JevSubmission.of(pr(MIXED_DIFF), CONTEXT, List.of(twice)));
    }

    @Test
    void checksShareOneRequestAndEachGetsOnlyItsOwnAnswers() {
        JevSubmission submission = JevSubmission.of(pr(MIXED_DIFF), CONTEXT,
                List.of(new TestAdequacyCheck(), new FixedCheck("other", "behaviour")));
        DecisionRequest request = submission.toDecisionRequest();

        assertEquals(List.of("test-adequacy.behaviour", "test-adequacy.tests-changed", "test-adequacy.reach",
                "test-adequacy.assert", "other.behaviour"), List.copyOf(request.questions().keySet()));

        DecisionResponse response = DecisionResponse.builder()
                .answer("test-adequacy.behaviour", YesNoAnswer.of(0.88))
                .answer("test-adequacy.tests-changed", YesNoAnswer.of(0.08))
                .answer("test-adequacy.reach", ChoiceAnswer.builder().value("no-test-changes")
                        .probabilities(Map.of("no-test-changes", 0.83, "some", 0.1, "undetermined", 0.07))
                        .build())
                .answer("test-adequacy.assert", ChoiceAnswer.builder().value("no-test-changes")
                        .probabilities(Map.of("no-test-changes", 0.88, "none", 0.12)).build())
                .answer("other.behaviour", YesNoAnswer.of(0.5))
                .build();
        List<CheckResult> results = submission.results(response);

        assertEquals(List.of(
                new CheckResult("test-adequacy", List.of(
                        new QuestionResult("test-adequacy", "behaviour", "behaviour",
                                "Does this PR change the runtime behaviour of the software?",
                                new QuestionResult.YesNo(0.88)),
                        new QuestionResult("test-adequacy", "tests-changed", "tests-changed",
                                TestAdequacyCheck.TESTS_CHANGED.text(), new QuestionResult.YesNo(0.08)),
                        new QuestionResult("test-adequacy", "reach", "reach", TestAdequacyCheck.REACH.text(),
                                new QuestionResult.Choice("no-test-changes", TestAdequacyCheck.REACH.options(),
                                        ordered("some", 0.1, "no-test-changes", 0.83, "undetermined", 0.07))),
                        new QuestionResult("test-adequacy", "assert", "assert", TestAdequacyCheck.ASSERTS.text(),
                                new QuestionResult.Choice("no-test-changes", TestAdequacyCheck.ASSERTS.options(),
                                        ordered("none", 0.12, "no-test-changes", 0.88))))),
                new CheckResult("other", List.of(new QuestionResult("other", "behaviour", "other behaviour", "Other?",
                        new QuestionResult.YesNo(0.5))))),
                results);
    }

    @Test
    void choiceKeepsJevsValueAndDeclaredOptionOrder() {
        JevSubmission submission = JevSubmission.of(pr(MIXED_DIFF), CONTEXT, List.of(new TestAdequacyCheck()));
        // jev's chosen value is kept even when another option has a higher probability: the app never picks.
        DecisionResponse response = DecisionResponse.builder()
                .answer("test-adequacy.behaviour", YesNoAnswer.of(0.5))
                .answer("test-adequacy.tests-changed", YesNoAnswer.of(0.9))
                .answer("test-adequacy.reach", ChoiceAnswer.builder().value("undetermined")
                        .probability("undetermined", 0.3).probability("all", 0.6)
                        .probability("no-test-changes", 0.1).build())
                .answer("test-adequacy.assert", ChoiceAnswer.builder().value("some")
                        .probability("some", 0.5).probability("all", 0.5).build())
                .build();

        List<QuestionResult> questions = submission.results(response).get(0).questions();
        QuestionResult.Choice reach = (QuestionResult.Choice) questions.get(2).answer();
        QuestionResult.Choice asserts = (QuestionResult.Choice) questions.get(3).answer();

        assertEquals("undetermined", reach.value());
        assertEquals(List.of("all", "no-test-changes", "undetermined"), List.copyOf(reach.probabilities().keySet()));
        assertEquals(List.of("all", "some", "none", "no-runtime-change", "no-test-changes", "undetermined"),
                List.copyOf(reach.options().keySet()));
        assertEquals("some", asserts.value());
        assertEquals(List.of("all", "some", "none", "no-runtime-change", "no-test-changes", "no-changed-code-reached",
                "undetermined"), List.copyOf(asserts.options().keySet()));
    }

    private static Map<String, Double> ordered(Object... pairs) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Double) pairs[i + 1]);
        }
        return map;
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    /** A test-only check asking fixed yes/no questions. */
    record FixedCheck(String id, String... questionIds) implements ClassificationCheck {

        @Override
        public void ask(PrContext pr, CheckQuestions questions) {
            for (String questionId : questionIds) {
                questions.yesNo(questionId, id + " " + questionId, YesNoQuestion.of("Other?"));
            }
        }
    }
}
