package io.quarkus.prreview.classification.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import io.quarkus.prreview.classification.api.CheckQuestions;
import io.quarkus.prreview.classification.api.CheckResult;
import io.quarkus.prreview.classification.api.ClassificationCheck;
import io.quarkus.prreview.classification.api.QuestionResult;
import io.quarkus.prreview.classification.checks.testadequacy.TestAdequacyCheck;
import io.quarkus.prreview.clients.gh.model.PullRequest;
import io.quarkus.prreview.pr.DiffFiles;
import io.quarkus.prreview.pr.model.PrContext;

class JevSubmissionTest {

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
        String input = JevSubmission.of(pr(MIXED_DIFF), List.of(new TestAdequacyCheck())).input();

        assertTrue(input.contains("- core/runtime/src/main/java/io/quarkus/Foo.java (modified)\n"), input);
        assertTrue(input.contains("- core/runtime/src/test/java/io/quarkus/FooTest.java (modified)\n"), input);
        assertTrue(input.endsWith("Unified diff:\n" + MIXED_DIFF), input);
        // The test file appears once in the list and 4 times in its own diff headers (diff --git a/ b/, ---, +++),
        // never in a separate tests section.
        assertEquals(1 + 4, count(input, "core/runtime/src/test/java/io/quarkus/FooTest.java"), input);
        assertFalse(input.contains("Secret title"), input);
    }

    @Test
    void sameTwoQuestionsWhateverTheDiffContains() {
        for (String diff : List.of(MIXED_DIFF, TESTS_ONLY_DIFF, DOCS_ONLY_DIFF)) {
            DecisionRequest request = JevSubmission.of(pr(diff), List.of(new TestAdequacyCheck())).toDecisionRequest();
            assertEquals(List.of("test-adequacy.behaviour", "test-adequacy.adequately-tested"),
                    List.copyOf(request.questions().keySet()));
            assertEquals(request.input(), JevSubmission.input(pr(diff)));
        }
    }

    @Test
    void duplicateQuestionIdIsRejected() {
        ClassificationCheck twice = new FixedCheck("dup", "q", "q");
        assertThrows(IllegalStateException.class, () -> JevSubmission.of(pr(MIXED_DIFF), List.of(twice)));
    }

    @Test
    void checksShareOneRequestAndEachGetsOnlyItsOwnAnswers() {
        JevSubmission submission = JevSubmission.of(pr(MIXED_DIFF),
                List.of(new TestAdequacyCheck(), new FixedCheck("other", "behaviour")));
        DecisionRequest request = submission.toDecisionRequest();

        assertEquals(List.of("test-adequacy.behaviour", "test-adequacy.adequately-tested", "other.behaviour"),
                List.copyOf(request.questions().keySet()));

        DecisionResponse response = DecisionResponse.builder()
                .answer("test-adequacy.behaviour", YesNoAnswer.of(0.88))
                .answer("test-adequacy.adequately-tested", YesNoAnswer.of(0.21))
                .answer("other.behaviour", YesNoAnswer.of(0.5))
                .build();
        List<CheckResult> results = submission.results(response);

        assertEquals(List.of(
                new CheckResult("test-adequacy", List.of(
                        new QuestionResult("test-adequacy", "behaviour", "behaviour",
                                "Does this PR change the runtime behaviour of the software?", 0.88),
                        new QuestionResult("test-adequacy", "adequately-tested", "adequately tested",
                                "Is the runtime behaviour changed by this PR adequately exercised by the tests that"
                                        + " this PR adds or modifies?",
                                0.21))),
                new CheckResult("other", List.of(new QuestionResult("other", "behaviour", "other behaviour", "Other?", 0.5)))),
                results);
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
