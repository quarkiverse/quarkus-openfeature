package io.quarkiverse.openfeature.gofeatureflag.test;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/gofeatureflag-url")
public class GoFeatureFlagUrlResource {
    @ConfigProperty(name = "quarkus.openfeature.gofeatureflag.url")
    String url;

    @GET
    public String url() {
        return url;
    }
}
