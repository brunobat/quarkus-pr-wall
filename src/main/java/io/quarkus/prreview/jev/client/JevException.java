package io.quarkus.prreview.jev.client;

/**
 * Base type for {@code jev} API failures (plan §2 error table).
 *
 * <p>{@link Retryable} (429 overloaded / 529) is retried with exponential backoff; everything else
 * ({@link Fatal}: 401 auth, 422 validation — a bug in our question JSON) aborts immediately and must
 * fail loudly, never silently pass (plan §10).
 */
public abstract sealed class JevException extends RuntimeException
        permits JevException.Retryable, JevException.Fatal {

    protected JevException(String message) {
        super(message);
    }

    /** 429 (rate limit) / 529 (overloaded) — safe to retry. */
    public static final class Retryable extends JevException {
        private final int status;

        public Retryable(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** 401 (auth) / 422 (validation) / other — not retryable; indicates a bug or misconfiguration. */
    public static final class Fatal extends JevException {
        private final int status;

        public Fatal(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
