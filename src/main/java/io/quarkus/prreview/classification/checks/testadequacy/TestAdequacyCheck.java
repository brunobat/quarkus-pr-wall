package io.quarkus.prreview.classification.checks.testadequacy;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.decision.request.YesNoQuestion;
import io.quarkus.prreview.classification.api.CheckQuestions;
import io.quarkus.prreview.classification.api.ClassificationCheck;
import io.quarkus.prreview.pr.model.PrContext;

/**
 * Triage-stage test adequacy: does the PR change runtime behaviour, and do the tests it adds or modifies
 * exercise that change? Both questions are asked for every PR, whatever the diff contains.
 */
@ApplicationScoped
public class TestAdequacyCheck implements ClassificationCheck {

    static final YesNoQuestion BEHAVIOUR = YesNoQuestion.builder()
            .text("Does this PR change the runtime behaviour of the software?")
            .yesWhen("it changes code that runs, configuration read at runtime, or dependency versions that are shipped")
            .noWhen("it only changes documentation, comments, formatting, tests, or build/CI tooling that does not"
                    + " affect the shipped artifact")
            .build();

    static final YesNoQuestion ADEQUATELY_TESTED = YesNoQuestion.builder()
            .text("Is the runtime behaviour changed by this PR adequately exercised by the tests that this PR adds"
                    + " or modifies?")
            .yesWhen("tests added or modified in this PR drive the changed code paths and assert on their outcome")
            .noWhen("the PR adds or modifies no test that reaches the changed code, tests only compile or"
                    + " instantiate it without asserting, or there is no runtime behaviour change to exercise")
            .build();

    @Override
    public String id() {
        return "test-adequacy";
    }

    @Override
    public void ask(PrContext pr, CheckQuestions questions) {
        questions.yesNo("behaviour", "behaviour", BEHAVIOUR);
        questions.yesNo("adequately-tested", "adequately tested", ADEQUATELY_TESTED);
    }
}
