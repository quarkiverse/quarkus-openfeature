package io.quarkiverse.openfeature.flagd.test;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/flagd-url")
public class FlagdUrlResource {
    @ConfigProperty(name = "quarkus.openfeature.flagd.url")
    String url;

    @GET
    public String url() {
        return url;
    }
}
