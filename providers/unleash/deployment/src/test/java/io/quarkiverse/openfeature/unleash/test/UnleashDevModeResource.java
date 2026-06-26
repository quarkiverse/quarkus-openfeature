package io.quarkiverse.openfeature.unleash.test;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import dev.openfeature.sdk.Client;

@Path("/flags")
public class UnleashDevModeResource {
    @Inject
    Client client;

    @GET
    @Path("/string/{key}")
    public String stringFlag(@PathParam("key") String key) {
        return client.getStringValue(key, "default");
    }
}
