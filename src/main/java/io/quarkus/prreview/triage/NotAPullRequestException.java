package io.quarkus.prreview.triage;

/**
 * GitHub answered that the configured repository has no pull request with this number. Nothing else is looked up for
 * it and no report is written; the run goes on with the next number.
 */
public class NotAPullRequestException extends RuntimeException {

    private final int number;

    public NotAPullRequestException(int number, Throwable cause) {
        super("not a pull request: " + number, cause);
        this.number = number;
    }

    public int number() {
        return number;
    }
}
