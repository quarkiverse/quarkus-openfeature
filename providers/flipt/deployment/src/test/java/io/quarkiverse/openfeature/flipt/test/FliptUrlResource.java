package io.quarkiverse.openfeature.flipt.test;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/flipt-url")
public class FliptUrlResource {
    @ConfigProperty(name = "quarkus.openfeature.flipt.url")
    String url;

    @GET
    public String url() {
        return url;
    }
}
