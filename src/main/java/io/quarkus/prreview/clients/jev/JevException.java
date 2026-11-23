package io.quarkus.prreview.clients.jev;

/**
 * A {@code jev} submission was not classified: over the size limit, or the call failed. The message carries
 * {@code jev}'s own error text when there is one.
 */
public class JevException extends RuntimeException {

    private final int submissionChars;
    private final int estimatedTokens;
    private final int limit;

    public JevException(String message, int submissionChars, int estimatedTokens, int limit, Throwable cause) {
        super(message, cause);
        this.submissionChars = submissionChars;
        this.estimatedTokens = estimatedTokens;
        this.limit = limit;
    }

    /** The measured submission size, in characters. */
    public int submissionChars() {
        return submissionChars;
    }

    /** The submission's estimated tokens, without the share kept for the answer. */
    public int estimatedTokens() {
        return estimatedTokens;
    }

    /** The configured size limit, in tokens. */
    public int limit() {
        return limit;
    }
}
