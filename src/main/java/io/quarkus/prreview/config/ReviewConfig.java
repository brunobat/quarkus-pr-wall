package io.quarkus.prreview.config;

import java.nio.file.Path;
import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Operational settings owned by the application.
 * <p>
 * Every value is declared here exactly once, with its purpose, the environment variable that sets it and
 * its default ({@code ${ENV_VAR:default}}). Code never repeats these values as literals.
 * None of these settings interprets {@code jev}'s answers: the app classifies, it does not decide.
 */
@ConfigMapping(prefix = "pr-review")
public interface ReviewConfig {

    /** Access to GitHub through the {@code gh} CLI (read-only). */
    Gh gh();

    /** Limits on what is sent to {@code jev}. */
    Jev jev();

    /** Who wrote a PR: limits on the lookups made to label its people. */
    Provenance provenance();

    /** Directory where the {@code review-<pr>.md} reports are written, relative to the working directory. */
    @WithDefault("reports")
    Path outputDir();

    interface Gh {

        /** The {@code owner/repo} passed with {@code -R} to every {@code gh} call. */
        @WithDefault("${GH_REPO:quarkusio/quarkus}")
        String repo();

        /** The {@code gh} executable to run. */
        @WithDefault("${GH_BINARY:gh}")
        String binary();

        /** How long one {@code gh} invocation may run before that PR is noted as not classified. */
        @WithDefault("${GH_TIMEOUT:60s}")
        Duration timeout();

        /**
         * How long {@code gh api --cache} keeps a per-person answer (org membership, commits before a base), so a person
         * seen in an earlier PR or run costs no request. A PR's own author association is never cached.
         */
        @WithDefault("${GH_CACHE_TTL:1h}")
        Duration cacheTtl();
    }

    interface Provenance {

        /**
         * The most commit authors looked up on GitHub for one PR, besides its author; the rest are labelled
         * {@code other (lookup limit)}. Bounds the calls a PR with many authors can cost.
         */
        @WithDefault("${PROVENANCE_MAX_PEOPLE:10}")
        int maxPeople();

        /**
         * The AI-declaration markers (e.g. {@code co-authored-by: claude}) looked for in the PR body and commit
         * messages: a file path, or {@code classpath:<resource>}. A missing file warns and counts as empty.
         */
        @WithDefault("${PROVENANCE_AI_SIGNALS:resource-files/ai-provenance-signals.txt}")
        String aiSignals();

        /**
         * The bot logins that are routine automation (e.g. {@code dependabot}), not coding agents: a file path, or
         * {@code classpath:<resource>}. A missing file warns and counts as empty, so every bot is then an agent.
         */
        @WithDefault("${PROVENANCE_AUTOMATION_ACCOUNTS:resource-files/automation-accounts.txt}")
        String automationAccounts();
    }

    interface Jev {

        /**
         * The largest {@code jev} submission, in characters of the serialized request body, that is sent.
         * Larger submissions are noted as not classified before sending, instead of being rejected by
         * {@code jev} or sent partially.
         */
        @WithDefault("${JEV_MAX_SUBMISSION_CHARS:60000}")
        int maxSubmissionChars();

        /**
         * The project context sent at the start of every {@code jev} submission: a file path, or
         * {@code classpath:<resource>} for a bundled one. It describes the repository's language, build and layout
         * conventions, never a PR or an answer.
         */
        @WithDefault("${JEV_PROJECT_CONTEXT:classpath:project-context/quarkusio-quarkus.txt}")
        String projectContext();
    }
}
