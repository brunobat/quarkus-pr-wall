package io.quarkus.prreview.classification.github.ci;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.prreview.classification.github.ci.model.CiCheck;
import io.quarkus.prreview.classification.github.ci.model.CiChecks;
import io.quarkus.prreview.classification.github.ci.model.FailedCheck;
import io.quarkus.prreview.classification.github.ci.model.FailureRow;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhCheck;
import io.quarkus.prreview.clients.gh.model.GhChecks;

/**
 * The CI classification source: the checks on a PR's head commit, with those that did not pass listed, or why they
 * are unavailable. GitHub's values are kept as returned; nothing is concluded from them. Never throws for gh.
 */
@ApplicationScoped
public class CiSource {

    private static final Logger LOG = Logger.getLogger(CiSource.class);

    /** GitHub's values a check has when it passed, was skipped, or reported neutrally. */
    static final Set<String> PASSED = Set.of("SUCCESS", "SKIPPED", "NEUTRAL");

    private final GhClient gh;

    public CiSource(GhClient gh) {
        this.gh = gh;
    }

    public SourceResult<CiChecks> gather(int pr) {
        try {
            return new SourceResult.Available<>(of(gh.checks(pr)));
        } catch (GhException e) {
            LOG.warnf(e, "PR #%d: checks unavailable", pr);
            return SourceResult.unavailable("gh: " + e.getMessage().strip().replaceAll("\\s+", " "));
        }
    }

    /** Every check as GitHub returned it, and the not-passed ones with a row saying no details are known. */
    static CiChecks of(GhChecks gh) {
        List<CiCheck> checks = gh.rollup().stream().map(CiSource::check).toList();
        List<FailedCheck> notPassed = checks.stream().filter(c -> !PASSED.contains(c.value()))
                .map(c -> new FailedCheck(c, List.of(FailureRow.withoutTest(FailureRow.DETAILS_UNAVAILABLE))))
                .toList();
        return new CiChecks(gh.headRefOid(), checks, notPassed);
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
