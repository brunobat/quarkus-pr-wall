package io.quarkus.prreview.classification.github.ci;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.prreview.classification.github.GitHubFactsService;
import io.quarkus.prreview.classification.github.model.Approval;
import io.quarkus.prreview.classification.github.model.CiCheck;
import io.quarkus.prreview.classification.github.model.CiChecks;
import io.quarkus.prreview.classification.github.model.FailedCheck;
import io.quarkus.prreview.classification.model.SourceResult;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhCheck;
import io.quarkus.prreview.clients.gh.model.GhCheckSuite;
import io.quarkus.prreview.clients.gh.model.GhChecks;
import io.quarkus.prreview.clients.gh.model.GhComment;
import io.quarkus.prreview.util.FailureLog;
import io.quarkus.prreview.util.PlainText;

/**
 * The CI classification source: the checks on a PR's head commit, with those that did not pass listed with their
 * failures from quarkus-bot's comments, the tests the bot lists as flaky, and whether a check suite on the head
 * commit awaits approval, or why the checks are unavailable. GitHub's values are kept as returned; nothing is
 * concluded from them. Never throws for gh.
 */
@ApplicationScoped
public class CiService {

    private static final Logger LOG = Logger.getLogger(CiService.class);

    /** GitHub's values a check has when it passed, was skipped, or reported neutrally. */
    static final Set<String> PASSED = Set.of("SUCCESS", "SKIPPED", "NEUTRAL");

    private final GhClient gh;
    private final GitHubFactsService facts;

    public CiService(GhClient gh, GitHubFactsService facts) {
        this.gh = gh;
        this.facts = facts;
    }

    public SourceResult<CiChecks> gather(int pr) {
        GhChecks checks;
        try {
            checks = facts.checks(pr).orElseGet(() -> gh.checks(pr));
        } catch (GhException e) {
            FailureLog.warn(LOG, "PR #" + pr + ": checks unavailable: gh", e);
            return SourceResult.unavailable("gh: " + PlainText.oneLine(e.getMessage()));
        }
        List<GhComment> comments = null;
        try {
            comments = facts.comments(pr).orElseGet(() -> gh.comments(pr));
        } catch (GhException e) {
            FailureLog.warn(LOG, "PR #" + pr + ": comments unavailable, so are failure details and flaky tests: gh",
                    e);
        }
        return new SourceResult.Available<>(of(checks, comments, approval(pr, checks.headRefOid())));
    }

    /** The head commit's approval state; a failed read is one warning line and leaves the checks as they are. */
    private Approval approval(int pr, String headCommit) {
        try {
            return approval(gh.checkSuites(headCommit));
        } catch (GhException e) {
            FailureLog.warn(LOG, "PR #" + pr + ": approval status unavailable: gh", e);
            return Approval.UNAVAILABLE;
        }
    }

    /**
     * {@link Approval#WAITING} when GitHub marks a suite as awaiting approval: conclusion {@code action_required} with
     * no check runs. A suite that has runs, or one that is only queued, is not that marking.
     */
    static Approval approval(List<GhCheckSuite> suites) {
        return suites.stream().anyMatch(s -> "action_required".equalsIgnoreCase(s.conclusion())
                && Integer.valueOf(0).equals(s.latestCheckRunsCount())) ? Approval.WAITING : Approval.NOT_WAITING;
    }

    /**
     * Every check as GitHub returned it, and the not-passed ones with their rows from quarkus-bot's comments.
     *
     * @param comments the PR's comments, or {@code null} when they could not be read
     * @param approval the head commit's approval state
     */
    static CiChecks of(GhChecks gh, List<GhComment> comments, Approval approval) {
        List<CiCheck> checks = gh.rollup().stream().map(CiService::check).toList();
        List<FailedCheck> notPassed = checks.stream().filter(c -> !PASSED.contains(c.value()))
                .map(c -> new FailedCheck(c, FailureDetails.rows(c, comments, gh.headRefOid(), checks))).toList();
        return new CiChecks(gh.headRefOid(), checks, notPassed, approval,
                comments == null ? null : FailureDetails.flaky(comments, gh.headRefOid(), checks));
    }

    private static CiCheck check(GhCheck check) {
        if (GhCheck.STATUS_CONTEXT.equals(check.typename())) {
            return new CiCheck(CiCheck.Kind.STATUS, orEmpty(check.context()), "", orEmpty(check.state()),
                    orEmpty(check.targetUrl()));
        }
        String value = check.conclusion() == null || check.conclusion().isEmpty() ? orEmpty(check.status())
                : check.conclusion();
        return new CiCheck(CiCheck.Kind.CHECK_RUN, orEmpty(check.name()), orEmpty(check.workflowName()), value,
                orEmpty(check.detailsUrl()));
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }
}
