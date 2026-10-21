package io.quarkus.prreview.facts.model;

import java.util.List;

/**
 * Author context queried via the GitHub API by login — never by reading PR branch content
 * (design §3.1, §8 invariant I-3). Purely informational / floor-only (design §6): it can only
 * <em>raise</em> review-priority, never lower it.
 *
 * @param priorMergedPrCount merged PRs by this author in the repo (0 ⇒ first-timer signal)
 * @param priorAreas         areas/modules the author has previously touched — passed to the
 *                           outside_prior_areas question as neutral reference data only
 *                           (plan §5 state exclusions)
 * @param accountAgeDays     account age in days (brand-new-account anomaly signal), -1 if unknown
 * @param resolved           false if the fact could not be fetched (degraded → advisory)
 */
public record AuthorHistory(
        int priorMergedPrCount,
        List<String> priorAreas,
        long accountAgeDays,
        boolean resolved) {

    public AuthorHistory {
        priorAreas = priorAreas == null ? List.of() : List.copyOf(priorAreas);
    }

    public boolean isBrandNewAccount() {
        return resolved && accountAgeDays >= 0 && accountAgeDays < 30;
    }

    public static AuthorHistory unknown() {
        return new AuthorHistory(0, List.of(), -1, false);
    }
}
