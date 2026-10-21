package io.quarkus.prreview.report.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * Anthropic Messages API client (plan §9). Opus is called <strong>only after</strong> findings exist
 * as structured data — it phrases decisions, it never makes one. Base URL configured via
 * {@code quarkus.rest-client.anthropic.url} (default {@code https://api.anthropic.com}).
 */
@RegisterRestClient(configKey = "anthropic")
@Path("/v1/messages")
public interface OpusMessageClient {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<MessageResponse> create(
            @HeaderParam("x-api-key") String apiKey,
            @HeaderParam("anthropic-version") String version,
            MessageRequest request);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MessageRequest(String model, int max_tokens, String system, List<Message> messages) {
    }

    record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageResponse(List<ContentBlock> content) {
        public String text() {
            if (content == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (ContentBlock b : content) {
                if ("text".equals(b.type()) && b.text() != null) {
                    sb.append(b.text());
                }
            }
            return sb.toString();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentBlock(String type, String text) {
    }
}
