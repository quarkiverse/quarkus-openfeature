package io.quarkiverse.openfeature.flagd.test;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import dev.openfeature.sdk.Client;
import io.smallrye.common.annotation.Identifier;

@Path("/flags")
public class FlagdMultiDomainDevModeResource {
    @Inject
    Client defaultClient;

    @Inject
    @Identifier("custom")
    Client customClient;

    @GET
    @Path("/default/string/{key}")
    public String defaultStringFlag(@PathParam("key") String key) {
        return defaultClient.getStringValue(key, "fallback");
    }

    @GET
    @Path("/custom/string/{key}")
    public String customStringFlag(@PathParam("key") String key) {
        return customClient.getStringValue(key, "fallback");
    }
}
