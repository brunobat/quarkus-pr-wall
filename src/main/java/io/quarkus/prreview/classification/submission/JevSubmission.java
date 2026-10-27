package io.quarkus.prreview.classification.submission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.model.decision.request.ChoiceQuestion;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.request.Question;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.DecisionResponse;
import io.quarkus.prreview.classification.api.CheckQuestions;
import io.quarkus.prreview.classification.api.CheckResult;
import io.quarkus.prreview.classification.api.ClassificationCheck;
import io.quarkus.prreview.classification.api.QuestionResult;
import io.quarkus.prreview.pr.model.ChangedFile;
import io.quarkus.prreview.pr.model.PrContext;

/**
 * The single {@code jev} submission for one PR: the shared input, added once, plus every check's questions.
 * Question ids are {@code <checkId>.<questionId>}; a duplicate id is rejected before anything is sent.
 */
public final class JevSubmission {

    private final String input;
    private final Map<String, Asked> questions = new LinkedHashMap<>();

    private JevSubmission(String input) {
        this.input = input;
    }

    /** Builds the submission: the shared input from the PR, then each check's questions in the order given. */
    public static JevSubmission of(PrContext pr, List<? extends ClassificationCheck> checks) {
        JevSubmission submission = new JevSubmission(input(pr));
        for (ClassificationCheck check : checks) {
            check.ask(pr, submission.questionsFor(check.id()));
        }
        return submission;
    }

    /**
     * The shared input: the changed-file list and the raw unified diff, each file appearing once in each.
     * The PR title, body and linked issues are deliberately not sent.
     */
    static String input(PrContext pr) {
        StringBuilder text = new StringBuilder("Changed files:\n");
        for (ChangedFile file : pr.changedFiles()) {
            text.append("- ").append(file.path()).append(" (").append(file.describe()).append(")\n");
        }
        text.append("\nUnified diff:\n").append(pr.pullRequest().diff());
        return text.toString();
    }

    private CheckQuestions questionsFor(String checkId) {
        return new CheckQuestions() {
            @Override
            public void yesNo(String questionId, String label, YesNoQuestion question) {
                add(new Asked(checkId, questionId, label, question));
            }

            @Override
            public void choice(String questionId, String label, ChoiceQuestion question) {
                add(new Asked(checkId, questionId, label, question));
            }
        };
    }

    private void add(Asked asked) {
        String id = asked.checkId() + "." + asked.questionId();
        if (questions.putIfAbsent(id, asked) != null) {
            throw new IllegalStateException("Duplicate jev question id: " + id);
        }
    }

    public String input() {
        return input;
    }

    public DecisionRequest toDecisionRequest() {
        DecisionRequest.Builder request = DecisionRequest.builder().input(input);
        questions.forEach((id, asked) -> request.question(id, asked.question()));
        return request.build();
    }

    /** Groups the answers back by check, unchanged, in the order the checks asked. */
    public List<CheckResult> results(DecisionResponse response) {
        Map<String, List<QuestionResult>> byCheck = new LinkedHashMap<>();
        questions.forEach((id, asked) -> byCheck.computeIfAbsent(asked.checkId(), k -> new ArrayList<>())
                .add(new QuestionResult(asked.checkId(), asked.questionId(), asked.label(), asked.question().text(),
                        answer(response, id, asked.question()))));
        return byCheck.entrySet().stream().map(e -> new CheckResult(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    private static QuestionResult.Answer answer(DecisionResponse response, String id, Question question) {
        if (question instanceof ChoiceQuestion choice) {
            ChoiceAnswer answer = response.choice(id);
            Map<String, Double> probabilities = new LinkedHashMap<>();
            choice.options().keySet().forEach(option -> {
                Double p = answer.probabilities().get(option);
                if (p != null) {
                    probabilities.put(option, p);
                }
            });
            return new QuestionResult.Choice(answer.value(), choice.options(),
                    Collections.unmodifiableMap(probabilities));
        }
        return new QuestionResult.YesNo(response.yesNo(id).probability());
    }

    private record Asked(String checkId, String questionId, String label, Question question) {
    }
}
