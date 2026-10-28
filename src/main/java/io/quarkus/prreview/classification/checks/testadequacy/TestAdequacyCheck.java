package io.quarkus.prreview.classification.checks.testadequacy;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.decision.request.ChoiceQuestion;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import io.quarkus.prreview.classification.api.CheckQuestions;
import io.quarkus.prreview.classification.api.ClassificationCheck;
import io.quarkus.prreview.pr.model.PrContext;

/**
 * Triage-stage test adequacy, as four independent questions: does the PR change runtime behaviour, does it add or
 * modify tests, how much of the changed code do those tests execute, and do they assert its outcome? All four are
 * asked for every PR, whatever the diff contains, and their answers are never combined.
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
            .text("How much of the changed runtime code do the tests this PR adds or modifies execute?")
            .option("all", "Every changed runtime code path is executed by a changed test.")
            .option("some", "Some changed runtime code paths are executed by a changed test; others are not.")
            .option("none", "Changed tests exist but execute none of the changed runtime code.")
            .option("not-applicable", "The PR changes no runtime behaviour, or adds or modifies no tests.")
            .option("undetermined",
                    "The diff does not give enough to tell, e.g. binary or generated changes, behaviour too indirect"
                            + " to trace, or tests that depend on code outside the diff.")
            .build();

    /** The {@code assert} question; {@code assert} is a Java keyword. */
    public static final ChoiceQuestion ASSERTS = ChoiceQuestion.builder()
            .text("Do the tests this PR adds or modifies assert the outcome of the changed code they execute?")
            .option("all", "Every changed path the changed tests execute has its outcome asserted.")
            .option("some", "Some executed changed paths are asserted; others are only called, built or smoke-run.")
            .option("none", "The changed tests execute changed code but assert none of its outcomes.")
            .option("not-applicable", "The changed tests execute no changed runtime code, or there are none.")
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
