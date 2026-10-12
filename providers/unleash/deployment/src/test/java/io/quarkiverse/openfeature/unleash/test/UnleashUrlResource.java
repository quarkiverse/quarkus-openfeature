package io.quarkiverse.openfeature.unleash.test;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/unleash-url")
public class UnleashUrlResource {
    @ConfigProperty(name = "quarkus.openfeature.unleash.url")
    String url;

    @GET
    public String url() {
        return url;
    }
}
