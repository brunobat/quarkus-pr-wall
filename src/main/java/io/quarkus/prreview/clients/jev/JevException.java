package io.quarkus.prreview.clients.jev;

/**
 * A {@code jev} submission was not classified: over the size limit, or the call failed. The message carries
 * {@code jev}'s own error text when there is one.
 */
public class JevException extends RuntimeException {

    private final int submissionChars;
    private final int limit;

    public JevException(String message, int submissionChars, int limit, Throwable cause) {
        super(message, cause);
        this.submissionChars = submissionChars;
        this.limit = limit;
    }

    /** The measured submission size, in characters. */
    public int submissionChars() {
        return submissionChars;
    }

    /** The configured size limit, in characters. */
    public int limit() {
        return limit;
    }
}
