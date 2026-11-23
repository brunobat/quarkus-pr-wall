package io.quarkus.prreview.classification.checks.testadequacy;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.decision.request.ChoiceQuestion;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import io.quarkus.prreview.classification.api.CheckQuestions;
import io.quarkus.prreview.classification.api.ClassificationCheck;
import io.quarkus.prreview.pr.model.PrContext;

/**
 * Triage-stage test adequacy, as four independent questions: does the PR change runtime behaviour, does it add or
 * modify tests, how much of the changed runtime code do those tests execute, and how much of what they execute do
 * they assert? All four are asked for every PR, whatever the diff contains, and their answers are never combined.
 * Each of {@code reach} and {@code assert} measures one thing; a measure with nothing to measure has its own option.
 */
@ApplicationScoped
public class TestAdequacyCheck implements ClassificationCheck {

    public static final YesNoQuestion BEHAVIOUR = YesNoQuestion.builder()
            .text("Does this PR change the runtime behaviour of the software?")
            .yesWhen("it changes code that runs, configuration read at runtime, or dependency versions that are shipped")
            .noWhen("it only changes documentation, comments, formatting, tests, or build/CI tooling that does not"
                    + " affect the shipped artifact")
            .build();

    public static final YesNoQuestion TESTS_CHANGED = YesNoQuestion.builder()
            .text("Does this PR add or modify tests?")
            .yesWhen("it adds or modifies files under a src/test/ directory")
            .noWhen("it changes no file under a src/test/ directory")
            .build();

    public static final ChoiceQuestion REACH = ChoiceQuestion.builder()
            .text("Of the runtime code this PR changes, how much do the tests this PR adds or modifies execute?")
            .option("all", "Every changed runtime code path is executed by a changed test.")
            .option("some", "Some changed runtime code paths are executed by a changed test; others are not.")
            .option("none", "No changed test executes any of the changed runtime code, including when the PR adds or"
                    + " modifies no tests.")
            .option("no-runtime-change", "The PR changes no runtime behaviour, so there is no changed runtime code to"
                    + " execute.")
            .option("undetermined",
                    "The diff does not give enough to tell, e.g. binary or generated changes, behaviour too indirect"
                            + " to trace, or tests that depend on code outside the diff.")
            .build();

    /** The {@code assert} question; {@code assert} is a Java keyword. */
    public static final ChoiceQuestion ASSERTS = ChoiceQuestion.builder()
            .text("Of the changed runtime code in test classes on this PR, how extensive are the test assertion changes"
                    + " outcome asserted by those tests?")
            .option("all", "EVERY changes in the runtime code includes tests assertions validating that runtime code change.")
            .option("some", "Some changes in the runtime path includes tests assertions validating that runtime code change.")
            .option("none", "No changes in the runtime path includes tests assertions validating that runtime code change.")
            .option("nothing-executed", "No need for test assertions because there was no runtime code affected by the PR.")
            .option("undetermined", "The diff does not give enough to tell.")
            .build();

    @Override
    public String id() {
        return "test-adequacy";
    }

    @Override
    public void ask(PrContext pr, CheckQuestions questions) {
        questions.yesNo("behaviour", "behaviour", BEHAVIOUR);
        questions.yesNo("tests-changed", "tests-changed", TESTS_CHANGED);
        questions.choice("reach", "reach", REACH);
        questions.choice("assert", "assert", ASSERTS);
    }
}
