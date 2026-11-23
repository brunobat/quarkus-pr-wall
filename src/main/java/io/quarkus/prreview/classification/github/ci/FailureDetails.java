package io.quarkus.prreview.classification.github.ci;

import java.util.List;
import java.util.Optional;

import io.quarkus.prreview.classification.github.ci.botcomment.BotCommentParser;
import io.quarkus.prreview.classification.github.ci.botcomment.BotStatusComment;
import io.quarkus.prreview.classification.github.ci.model.CiCheck;
import io.quarkus.prreview.classification.github.ci.model.FailureRow;
import io.quarkus.prreview.clients.gh.model.GhComment;

/**
 * The rows of a check that did not pass, from quarkus-bot's status comment for the check's workflow on the head
 * commit only: one per failure the bot lists under the check's job, or one saying why there are none.
 */
final class FailureDetails {

    private FailureDetails() {
    }

    /**
     * @param comments the PR's comments, or {@code null} when they could not be read
     */
    static List<FailureRow> rows(CiCheck check, List<GhComment> comments, String head) {
        if (comments == null) {
            return unavailable();
        }
        if (!BotCommentParser.hasBotComment(comments)) {
            return List.of(FailureRow.withoutTest(FailureRow.NO_SOURCE));
        }
        if (check.kind() != CiCheck.Kind.CHECK_RUN || check.workflow().isEmpty()) {
            return unavailable();
        }
        Optional<BotStatusComment> comment = BotCommentParser.forHead(comments, check.workflow(), head);
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
