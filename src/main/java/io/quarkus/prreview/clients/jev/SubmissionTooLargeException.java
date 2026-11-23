package io.quarkus.prreview.clients.jev;

import java.util.Locale;

import io.quarkus.prreview.config.ReviewConfig;

/** A submission whose estimated tokens, with the answer's share, are over the configured limit; it was not sent. */
public class SubmissionTooLargeException extends JevException {

    public SubmissionTooLargeException(int submissionChars, int estimatedTokens, int limit) {
        super(String.format(Locale.ROOT,
                "submission is %d characters, estimated at %d tokens (%.1f characters per token); with %d tokens kept for the answer it is over the limit of %d tokens",
                submissionChars, estimatedTokens, ReviewConfig.Jev.CHARS_PER_TOKEN, ReviewConfig.Jev.ANSWER_TOKENS,
                limit), submissionChars, estimatedTokens, limit, null);
    }
}
