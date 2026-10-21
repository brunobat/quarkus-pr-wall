package io.quarkus.prreview.jev.client;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/**
 * Maps {@code jev} HTTP error responses to the typed {@link JevException} hierarchy so the
 * {@code @Retry}/{@code abortOn} policy in {@link JevService} can distinguish retryable overload
 * (429/529) from fatal misconfiguration (401/422) — plan §2, §10.
 */
public class JevExceptionMapper implements ResponseExceptionMapper<JevException> {

    @Override
    public boolean handles(int status, jakarta.ws.rs.core.MultivaluedMap<String, Object> headers) {
        return status >= 400;
    }

    @Override
    public JevException toThrowable(Response response) {
        int status = response.getStatus();
        String body = safeBody(response);
        return switch (status) {
            case 429, 529 -> new JevException.Retryable(status, "jev overloaded/rate-limited (" + status + "): " + body);
            case 401 -> new JevException.Fatal(status, "jev auth failed (401): check TYPESAFE_API_KEY");
            case 422 -> new JevException.Fatal(status, "jev rejected the question JSON (422) — fix the request: " + body);
            default -> new JevException.Fatal(status, "jev request failed (" + status + "): " + body);
        };
    }

    private static String safeBody(Response response) {
        try {
            if (response.hasEntity()) {
                return response.readEntity(String.class);
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "<no body>";
    }
}
