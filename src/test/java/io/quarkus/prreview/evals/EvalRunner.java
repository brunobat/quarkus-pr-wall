package io.quarkus.prreview.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.prreview.facts.services.NoNetworkGhClient;
import io.quarkus.prreview.jev.model.Answer;
import io.quarkus.prreview.jev.model.JevResponse;
import io.quarkus.prreview.jev.services.FixtureJevService;
import io.quarkus.prreview.review.model.ReviewResult;
import io.quarkus.prreview.review.services.ReviewEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Drives one {@link EvalCase} through the <em>real</em> review path (plan §10, §13):
 * {@code gh → facts → triage → deep fan-out → combine}.
 *
 * <p>Only the two external boundaries are replaced — {@code gh} (by the recorded PR) and {@code jev} (by
 * the case's canned answers). Everything in between is production code: the diff is parsed and classified
 * for real, trust facts are resolved for real, and the whole interpretation layer runs unmodified. An eval
 * therefore catches a regression anywhere in the chain, not just in the gate arithmetic.
 */
@ApplicationScoped
public class EvalRunner {

    @Inject
    ReviewEngine engine;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Run the case end to end. Always paired with {@link #reset()} in an {@code @AfterEach}. */
    public ReviewResult run(EvalCase c) {
        String diff = EvalCases.diff(c);
        NoNetworkGhClient.install(args -> gh(c, diff, args));
        FixtureJevService.install(questions -> respond(c, questions));
        try {
            return engine.review(c.pr().number(), c.runFullDepth())
                    .await().atMost(Duration.ofSeconds(30));
        } finally {
            reset();
        }
    }

    public static void reset() {
        NoNetworkGhClient.clear();
        FixtureJevService.clear();
    }

    // --- the gh boundary -------------------------------------------------------------------------

    /** Canned stdout for every read-only {@code gh} command the facts layer issues (design §3.1). */
    private String gh(EvalCase c, String diff, String... args) {
        String sub = args.length > 0 ? args[0] : "";
        String verb = args.length > 1 ? args[1] : "";
        if (sub.equals("api") && verb.equals("graphql")) {
            return graphqlPr(c);
        }
        if (sub.equals("pr") && verb.equals("diff")) {
            return diff;
        }
        if (sub.equals("search")) {
            return mergedPrSearch(c.author().mergedPrs());
        }
        if (sub.equals("api") && verb.startsWith("users/")) {
            return "\"" + Instant.now().minus(Duration.ofDays(c.author().accountAgeDays())) + "\"";
        }
        if (sub.equals("api") && verb.contains("/contents/")) {
            return committersFile(c);
        }
        throw new IllegalStateException("eval fixture has no canned gh response for: gh " + String.join(" ", args));
    }

    /**
     * The {@code gh api graphql} envelope {@code FetchPr} parses: {@code data.pr<N>.pullRequest}. A case
     * holds one PR, so one alias is enough — the multi-alias and missing-alias shapes are covered by
     * {@link io.quarkus.prreview.facts.services.FetchPrTest} against the parser directly.
     */
    private String graphqlPr(EvalCase c) {
        EvalCase.Pr pr = c.pr();
        ObjectNode n = mapper.createObjectNode();
        n.put("number", pr.number());
        n.put("title", pr.title() == null ? "" : pr.title());
        n.put("body", pr.body() == null ? "" : pr.body());
        ObjectNode author = n.putObject("author");
        author.put("__typename", pr.isBot() ? "Bot" : "User");
        author.put("login", pr.author());
        n.put("baseRefName", pr.baseBranch());
        n.put("headRefName", pr.headBranch() == null ? "" : pr.headBranch());
        n.put("baseRefOid", pr.baseSha());
        n.put("headRefOid", pr.headSha());
        ArrayNode labels = n.putObject("labels").putArray("nodes");
        pr.labels().forEach(l -> labels.addObject().put("name", l));
        n.putObject("closingIssuesReferences").putArray("nodes");
        ArrayNode commits = n.putObject("commits").putArray("nodes");
        pr.commits().forEach(commit -> commits.addObject().set("commit", commitNode(commit)));

        ObjectNode root = mapper.createObjectNode();
        root.putObject("data").putObject("pr" + pr.number()).set("pullRequest", n);
        return root.toString();
    }

    private ObjectNode commitNode(EvalCase.Commit commit) {
        ObjectNode n = mapper.createObjectNode();
        n.put("oid", text(commit.oid()));
        n.put("messageHeadline", text(commit.messageHeadline()));
        n.put("messageBody", text(commit.messageBody()));
        n.put("authoredDate", text(commit.authoredDate()));
        n.put("committedDate", text(commit.committedDate()));
        n.putObject("parents").put("totalCount", commit.parentCount() == null ? 1 : commit.parentCount());
        ObjectNode author = n.putObject("author");
        author.put("name", text(commit.authorName()));
        author.putObject("user").put("login", text(commit.authorLogin()));
        ObjectNode committer = n.putObject("committer");
        committer.put("name", text(commit.committerName()));
        committer.putObject("user").put("login", text(commit.committerLogin()));
        return n;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private String mergedPrSearch(int count) {
        ArrayNode arr = mapper.createArrayNode();
        for (int i = 0; i < count; i++) {
            arr.addObject().put("number", 1000 + i);
        }
        return arr.toString();
    }

    /**
     * {@code gh api …/contents/COMMITTERS.adoc --jq .content} returns base64. A non-committer case still
     * returns a well-formed file — the author is simply absent from it, which is the real-world shape.
     */
    private String committersFile(EvalCase c) {
        String body = "= Committers\n\n* alice\n* bob\n"
                + (c.author().committer() ? "* " + c.pr().author() + "\n" : "");
        return Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
    }

    // --- the jev boundary ------------------------------------------------------------------------

    /**
     * Answer exactly the ids this question block asks for and that the case defines. A question the case
     * leaves out comes back unanswered, which is the honest representation of "the fixture has no opinion"
     * — the interpretation layer treats a missing answer as uncertainty, never as a clearance (I-5).
     */
    private JevResponse respond(EvalCase c, JsonNode questions) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        for (Iterator<String> it = asked(questions); it.hasNext(); ) {
            String id = it.next();
            EvalCase.FixtureAnswer fa = c.answers().get(id);
            if (fa != null) {
                answers.put(id, toAnswer(fa));
            }
        }
        return new JevResponse("jev-fixture", answers, new JevResponse.Usage(0, 0));
    }

    /** The question block is the externalized {@code questions/*.json} map: its field names are the ids. */
    private static Iterator<String> asked(JsonNode questions) {
        return questions == null ? java.util.Collections.emptyIterator() : questions.fieldNames();
    }

    private static Answer toAnswer(EvalCase.FixtureAnswer fa) {
        String type = fa.type().toLowerCase(Locale.ROOT);
        return new Answer(type, fa.choice(), fa.score(), fa.noul(), null, fa.confidence(), null);
    }
}
