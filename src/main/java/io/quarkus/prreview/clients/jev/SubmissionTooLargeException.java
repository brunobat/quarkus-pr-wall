package io.quarkus.prreview.clients.jev;

/** A submission over the configured size limit; it was not sent. */
public class SubmissionTooLargeException extends JevException {

    public SubmissionTooLargeException(int submissionChars, int limit) {
        super("submission is %d characters, over the limit of %d".formatted(submissionChars, limit), submissionChars,
                limit, null);
    }
}
