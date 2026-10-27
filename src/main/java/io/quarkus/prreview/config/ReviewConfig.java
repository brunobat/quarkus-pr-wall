package io.quarkus.prreview.config;

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

    /** Directory where the {@code review-<pr>.md} reports are written, relative to the working directory. */
    @WithDefault("reports")
    String outputDir();

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
    }

    interface Jev {

        /**
         * The largest {@code jev} submission, in characters of the serialized request body, that is sent.
         * Larger submissions are noted as not classified before sending, instead of being rejected by
         * {@code jev} or sent partially.
         */
        @WithDefault("${JEV_MAX_SUBMISSION_CHARS:60000}")
        int maxSubmissionChars();
    }
}
