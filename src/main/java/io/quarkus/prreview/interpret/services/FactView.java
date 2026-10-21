package io.quarkus.prreview.interpret.services;

import io.quarkus.prreview.facts.model.Facts;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic-fact predicate source for the rule engine (plan §5.6). Rules may read only these
 * named facts and banded answers — never raw PR text or another pipeline's output. That is enforced by
 * construction: no such source is exposed here (upholds invariants I-3, I-4).
 *
 * <p>Trust/identity facts appear here (committer status, first-timer, anomaly) precisely because they
 * are consumed <em>only</em> by the interpretation layer as deterministic gates, never by a
 * {@code jev} state (invariants I-2, I-3).
 *
 * <p>The authorship-provenance facts are here on the same terms, with one extra restriction:
 * {@code declares_ai_assistance} is a voluntary declaration, so a rule reading it may only
 * {@code escalate} (invariant I-7, enforced by {@link io.quarkus.prreview.interpret.rules.services.RulesetLint}).
 */
public final class FactView {

    /**
     * The closed set of fact names a rule may reference (plan §5.6). The {@link RulesetLint} validates
     * {@code fact:} predicates against this set, so a rule referencing an unknown fact fails the build.
     */
    public static final Set<String> KNOWN = Set.of(
            "is_committer", "is_non_committer", "is_first_timer", "is_backport",
            "has_runtime_change", "has_security_file", "first_timer_touches_security",
            "all_runtime_exempt", "has_non_exempt_runtime_hunk", "touches_existing_test",
            "brand_new_account", "has_pr_text", "has_docs_file", "committer_status", "base_branch",
            "author_is_bot", "author_is_allowlisted_bot", "declares_ai_assistance");

    private final Map<String, Boolean> bools = new HashMap<>();
    private final Map<String, String> strings = new HashMap<>();

    public static FactView of(Facts f) {
        FactView v = new FactView();
        var committer = f.committerStatus();
        v.bools.put("is_committer", committer.isCommitter());
        v.bools.put("is_non_committer", committer.isNonCommitter());
        v.bools.put("is_first_timer", committer.isFirstTimer());
        v.bools.put("is_backport", f.isBackport());
        v.bools.put("has_runtime_change", f.hasRuntimeChange());
        v.bools.put("has_security_file", f.hasSecuritySensitiveFile());
        v.bools.put("first_timer_touches_security", f.firstTimerTouchingSecurity());
        v.bools.put("all_runtime_exempt", f.changeClassification().allRuntimeExempt());
        v.bools.put("has_non_exempt_runtime_hunk", f.changeClassification().hasNonExemptRuntimeHunk());
        v.bools.put("touches_existing_test", f.touchesExistingTest());
        v.bools.put("brand_new_account", f.authorHistory().isBrandNewAccount());
        v.bools.put("has_pr_text", f.pr().hasText());
        v.bools.put("has_docs_file", f.hasDocsFile());
        var provenance = f.provenance();
        v.bools.put("author_is_bot", provenance.authorIsBot());
        v.bools.put("author_is_allowlisted_bot", provenance.authorIsAllowlistedBot());
        // I-7: a declaration may only route to a deeper look. RulesetLint rejects any rule that reads
        // this fact with an effect other than `escalate`.
        v.bools.put("declares_ai_assistance", provenance.declaresAiAssistance());
        v.strings.put("committer_status", committer.status().name().toLowerCase(java.util.Locale.ROOT));
        v.strings.put("base_branch", f.pr().baseBranch() == null ? "" : f.pr().baseBranch());
        return v;
    }

    public boolean bool(String name) {
        return bools.getOrDefault(name, false);
    }

    public boolean has(String name) {
        return bools.containsKey(name) || strings.containsKey(name);
    }

    public String string(String name) {
        return strings.getOrDefault(name, "");
    }

    /** Evaluate a {@code fact: X equals: Y} predicate. {@code true}/{@code false} match boolean facts. */
    public boolean matches(String name, String equals) {
        if (bools.containsKey(name)) {
            boolean expected = Boolean.parseBoolean(equals);
            return bools.get(name) == expected;
        }
        if (strings.containsKey(name)) {
            return strings.get(name).equalsIgnoreCase(equals);
        }
        return false;
    }

    // Visible for tests / programmatic construction.
    public FactView put(String name, boolean value) {
        bools.put(name, value);
        return this;
    }

    public FactView put(String name, String value) {
        strings.put(name, value);
        return this;
    }
}
