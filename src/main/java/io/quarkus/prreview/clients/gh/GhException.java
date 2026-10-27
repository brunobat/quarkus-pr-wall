package io.quarkus.prreview.clients.gh;

/** A {@code gh} invocation failed; the message carries {@code gh}'s own error text. */
public class GhException extends RuntimeException {

    public GhException(String message) {
        super(message);
    }

    public GhException(String message, Throwable cause) {
        super(message, cause);
    }
}
