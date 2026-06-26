package io.quarkiverse.openfeature.it.unleash;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ImmutableContext;

@Path("/unleash")
@ApplicationScoped
public class UnleashResource {
    @Inject
    Client client;

    @GET
    @Path("/boolean/{name}")
    public boolean booleanFlag(@PathParam("name") String name) {
        return client.getBooleanValue(name, false);
    }

    @GET
    @Path("/string/{name}")
    public String stringFlag(@PathParam("name") String name) {
        return client.getStringValue(name, "default");
    }

    @GET
    @Path("/integer/{name}")
    public int integerFlag(@PathParam("name") String name) {
        return client.getIntegerValue(name, 0);
    }

    @GET
    @Path("/double/{name}")
    public double doubleFlag(@PathParam("name") String name) {
        return client.getDoubleValue(name, 0.0);
    }

    @GET
    @Path("/targeted/string/{name}")
    public String targetedStringFlag(@PathParam("name") String name,
            @QueryParam("targetingKey") String targetingKey) {
        return client.getStringValue(name, "default", new ImmutableContext(targetingKey));
    }
}
