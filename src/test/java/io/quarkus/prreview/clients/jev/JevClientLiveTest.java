package io.quarkus.prreview.clients.jev;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.typesafe.TypeSafeDecisionModel;
import io.quarkus.prreview.classification.checks.TestAdequacyService;
import io.quarkus.prreview.classification.model.CheckResult;
import io.quarkus.prreview.classification.model.Classification;
import io.quarkus.prreview.classification.model.ProjectContext;
import io.quarkus.prreview.classification.model.QuestionResult;
import io.quarkus.prreview.classification.submission.JevSubmission;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhClientTest;
import io.quarkus.prreview.clients.jev.model.JevDecision;
import io.quarkus.prreview.report.ReportWriter;
import io.quarkus.prreview.report.TerminalRenderer;
import io.quarkus.prreview.report.summary.FallbackSummary;
import io.quarkus.prreview.triage.DiffFiles;
import io.quarkus.prreview.triage.model.PrContext;
import io.quarkus.prreview.triage.model.PrOutcome;
import io.quarkus.prreview.triage.model.PullRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the question set to the real {@code jev} for real PRs, read with the installed {@code gh} (read-only calls),
 * and prints each PR's answers as the terminal shows them, with every choice's probabilities, for a human to judge.
 * It checks only that every question is answered with a declared option or a probability, never the values. A PR
 * over the submission limit is printed as not sent. Claude is never called.
 * <p>
 * Runs only with {@code -Plive} and {@code TYPESAFE_API_KEY} set; otherwise it is skipped. PR 58508 is always
 * included; more are given with {@code -Dlive.prs=<n>,<n>,...}.
 */
@Tag("live")
class JevClientLiveTest {

    /** A large PR, all checks green, with flaky tests: expected over the default limit, so not sent. */
    static final int LARGE_PR = 58508;
    static final int DEFAULT_LIMIT = 60_000;

    @Test
    void everyQuestionIsAnsweredForRealPrs() throws IOException {
        String key = System.getenv("TYPESAFE_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "TYPESAFE_API_KEY is not set");
        String modelName = env("JEV_MODEL", "jev-latest");
        DecisionModel model = TypeSafeDecisionModel.builder().httpClientBuilder(new JdkHttpClientBuilder())
                .baseUrl(env("JEV_URL", "https://api.typesafe.ai")).apiKey(key).modelName(modelName)
                .timeout(Duration.ofMinutes(2)).build();
        JevClient jev = new JevClient(model, JevClientTest.config(DEFAULT_LIMIT), modelName, key);
        GhClient gh = new GhClient(GhClientTest.config("gh", Duration.ofSeconds(60)), JsonMapper.builder().build());
        ProjectContext context = projectContext();
        TestAdequacyService check = new TestAdequacyService();

        for (int number : prs()) {
            PullRequest pr = gh.withDiff(gh.metadata(number));
            PrContext prContext = new PrContext(pr, DiffFiles.parse(pr.diff()));
            JevSubmission submission = JevSubmission.of(prContext, context, List.of(check));
            JevDecision decision;
            try {
                decision = jev.decide(submission.toDecisionRequest());
            } catch (SubmissionTooLargeException e) {
                System.out.println("PR #" + number + ": not sent: " + e.getMessage() + "\n");
                continue;
            }
            Classification classification = new Classification(prContext, submission.results(decision.response()),
                    decision.response().modelName(), decision.response().tokenUsage(), decision.submissionChars(),
                    decision.estimatedTokens(), decision.limit(), context);
            print(number, pr.title(), classification);
            assertAnswered(number, classification);
        }
    }

    private static void print(int number, String title, Classification classification) {
        String block = TerminalRenderer.render(PrOutcome.answered(classification,
                FallbackSummary.of(classification)), new ReportWriter.Written(Path.of("-")));
        StringBuilder out = new StringBuilder(block.lines().takeWhile(l -> !l.startsWith("Summary"))
                .reduce("", (a, b) -> a + b + "\n"));
        out.append("  title: ").append(title).append('\n');
        for (CheckResult result : classification.checks()) {
            for (QuestionResult question : result.questions()) {
                if (question.answer() instanceof QuestionResult.Choice choice) {
                    out.append("  ").append(question.label()).append(": ").append(choice.probabilities())
                            .append('\n');
                }
            }
        }
        System.out.println(out);
    }

    private static void assertAnswered(int number, Classification classification) {
        for (CheckResult result : classification.checks()) {
            for (QuestionResult question : result.questions()) {
                String where = "PR #" + number + " " + question.label();
                switch (question.answer()) {
                    case QuestionResult.YesNo yesNo -> assertTrue(
                            !Double.isNaN(yesNo.probability()) && !Double.isInfinite(yesNo.probability()), where);
                    case QuestionResult.Choice choice -> {
                        assertTrue(choice.options().containsKey(choice.value()), where + ": " + choice.value());
                        for (Map.Entry<String, Double> p : choice.probabilities().entrySet()) {
                            assertTrue(choice.options().containsKey(p.getKey()), where + ": " + p.getKey());
                        }
                    }
                }
            }
        }
    }

    static List<Integer> prs() {
        List<Integer> prs = new ArrayList<>(List.of(LARGE_PR));
        String more = System.getProperty("live.prs", "");
        Arrays.stream(more.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(Integer::valueOf)
                .filter(n -> !prs.contains(n)).forEach(prs::add);
        if (prs.size() == 1) {
            System.out.println("Only PR " + LARGE_PR + "; add more with -Dlive.prs=<n>,<n>,...\n");
        }
        return prs;
    }

    private static ProjectContext projectContext() throws IOException {
        String path = "project-context/quarkusio-quarkus.txt";
        try (InputStream in = JevClientLiveTest.class.getClassLoader().getResourceAsStream(path)) {
            return new ProjectContext("classpath:" + path, new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .strip());
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
