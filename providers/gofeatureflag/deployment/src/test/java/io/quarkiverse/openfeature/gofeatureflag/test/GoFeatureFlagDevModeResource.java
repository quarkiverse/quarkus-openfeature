package io.quarkiverse.openfeature.gofeatureflag.test;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import dev.openfeature.sdk.Client;

@Path("/flags")
public class GoFeatureFlagDevModeResource {
    @Inject
    Client client;

    @GET
    @Path("/string/{key}")
    public String stringFlag(@PathParam("key") String key) {
        return client.getStringValue(key, "default");
    }
}
