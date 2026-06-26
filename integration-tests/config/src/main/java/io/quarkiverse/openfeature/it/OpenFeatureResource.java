package io.quarkiverse.openfeature.it;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import dev.openfeature.sdk.Client;
import io.smallrye.common.annotation.Identifier;

@Path("/openfeature")
@ApplicationScoped
public class OpenFeatureResource {
    @Inject
    Client defaultClient;

    @Inject
    @Identifier("custom")
    Client customClient;

    @GET
    @Path("/default/{name}")
    public String flag(@PathParam("name") String name) {
        return defaultClient.getStringValue(name, "default");
    }

    @GET
    @Path("/custom/{name}")
    public String customFlag(@PathParam("name") String name) {
        return customClient.getStringValue(name, "default");
    }
}
