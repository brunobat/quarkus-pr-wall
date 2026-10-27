package io.quarkus.prreview.classification.checks.testadequacy;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.decision.request.ChoiceQuestion;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import io.quarkus.prreview.classification.api.CheckQuestions;
import io.quarkus.prreview.classification.api.ClassificationCheck;
import io.quarkus.prreview.pr.model.PrContext;

/**
 * Triage-stage test adequacy: does the PR change runtime behaviour, and how do the tests it adds or modifies relate
 * to that change? Both questions are asked for every PR, whatever the diff contains.
 */
@ApplicationScoped
public class TestAdequacyCheck implements ClassificationCheck {

    public static final YesNoQuestion BEHAVIOUR = YesNoQuestion.builder()
            .text("Does this PR change the runtime behaviour of the software?")
            .yesWhen("it changes code that runs, configuration read at runtime, or dependency versions that are shipped")
            .noWhen("it only changes documentation, comments, formatting, tests, or build/CI tooling that does not"
                    + " affect the shipped artifact")
            .build();

    public static final ChoiceQuestion TESTS = ChoiceQuestion.builder()
            .text("How do the tests that this PR adds or modifies relate to the runtime behaviour it changes?")
            .option("no-runtime-change",
                    "The PR changes no runtime behaviour, so there is nothing for tests to exercise.")
            .option("no-test-changes", "The PR changes runtime behaviour but adds or modifies no tests.")
            .option("tests-unrelated", "Tests were added or modified, but none reach the changed code.")
            .option("tests-without-assertions",
                    "Changed tests reach the changed code but only compile, instantiate or smoke-run it without"
                            + " asserting its outcome.")
            .option("partially-exercised",
                    "Changed tests exercise and assert some of the changed behaviour; the rest has no test at all.")
            .option("multiple-gaps",
                    "Different parts of the change have different gaps, e.g. one changed path has no test, another"
                            + " is reached only by an unrelated test, a third is only called without assertions.")
            .option("fully-exercised", "Changed tests drive all the changed code paths and assert their outcomes.")
            .option("undetermined",
                    "The diff does not give enough to tell, e.g. binary or generated changes, behaviour too indirect"
                            + " to trace, or tests that depend on code outside the diff.")
            .build();

    @Override
    public String id() {
        return "test-adequacy";
    }

    @Override
    public void ask(PrContext pr, CheckQuestions questions) {
        questions.yesNo("behaviour", "behaviour", BEHAVIOUR);
        questions.choice("tests", "tests", TESTS);
    }
}
