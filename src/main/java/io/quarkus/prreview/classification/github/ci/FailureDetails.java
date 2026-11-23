package io.quarkus.prreview.classification.github.ci;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkus.prreview.classification.github.ci.botcomment.BotCommentParser;
import io.quarkus.prreview.classification.github.ci.botcomment.BotStatusComment;
import io.quarkus.prreview.classification.github.ci.model.CiCheck;
import io.quarkus.prreview.classification.github.ci.model.FailureRow;
import io.quarkus.prreview.clients.gh.model.GhComment;

/**
 * The rows of a check that did not pass, from quarkus-bot's status comment for the check's workflow on the head
 * commit only: one per failure the bot lists under the check's job, or one saying why there are none. The comment is
 * the one for a run the head commit's checks link to, when there is one.
 */
final class FailureDetails {

    private FailureDetails() {
    }

    /** {@code actions/runs/<id>} in a check's link: the workflow run the check belongs to. */
    private static final Pattern RUN = Pattern.compile("/actions/runs/([0-9]+)(?:/|$)");

    /**
     * The status comment for {@code workflow} on the head commit, preferring one for a run that a check of that
     * workflow on the head commit links to.
     */
    static Optional<BotStatusComment> comment(List<GhComment> comments, String workflow, String head,
            List<CiCheck> checks) {
        Set<String> runIds = new HashSet<>();
        for (CiCheck check : checks) {
            Matcher run = RUN.matcher(check.link());
            if (check.kind() == CiCheck.Kind.CHECK_RUN && check.workflow().equals(workflow) && run.find()) {
                runIds.add(run.group(1));
            }
        }
        return BotCommentParser.forHead(comments, workflow, head, runIds);
    }

    /**
     * @param comments the PR's comments, or {@code null} when they could not be read
     * @param checks every check on the head commit, whose links name the head commit's workflow runs
     */
    static List<FailureRow> rows(CiCheck check, List<GhComment> comments, String head, List<CiCheck> checks) {
        if (comments == null) {
            return unavailable();
        }
        if (!BotCommentParser.hasBotComment(comments)) {
            return List.of(FailureRow.withoutTest(FailureRow.NO_SOURCE));
        }
        if (check.kind() != CiCheck.Kind.CHECK_RUN || check.workflow().isEmpty()) {
            return unavailable();
        }
        Optional<BotStatusComment> comment = comment(comments, check.workflow(), head, checks);
        List<BotStatusComment.Entry> entries = comment.map(c -> c.failures().get(check.name())).orElse(null);
        if (entries == null || entries.isEmpty()) {
            return unavailable();
        }
        BotStatusComment found = comment.get();
        String scan = found.scanLinks().get(check.name());
        return entries.stream().map(e -> e.test() == null ? new FailureRow(null, null, e.text(), null, scan)
                : new FailureRow(e.test(), found.isFlaky(check.name(), e.test()), e.text(), e.historyLink(), scan))
                .toList();
    }

    private static List<FailureRow> unavailable() {
        return List.of(FailureRow.withoutTest(FailureRow.DETAILS_UNAVAILABLE));
    }
}
