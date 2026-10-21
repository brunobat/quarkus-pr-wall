package io.quarkus.prreview.jev.client;


import io.quarkus.prreview.jev.model.JevRequest;
import io.quarkus.prreview.jev.model.JevResponse;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Type-safe {@code jev} REST client (plan §3, §10). Base URL is configured via
 * {@code quarkus.rest-client.jev.url} (default {@code https://api.typesafe.ai}).
 *
 * <p>The {@code Authorization: Bearer …} header is supplied per-call by {@link JevService} rather
 * than baked into a provider, keeping the secret flowing from config through one place.
 */
@RegisterRestClient(configKey = "jev")
@RegisterProvider(JevExceptionMapper.class)
@Path("/v1/systemone")
public interface JevClient {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<JevResponse> evaluate(@HeaderParam("Authorization") String authorization, JevRequest request);
}
