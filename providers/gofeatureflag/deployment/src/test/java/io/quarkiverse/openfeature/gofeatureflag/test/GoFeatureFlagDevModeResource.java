package io.quarkiverse.openfeature.gofeatureflag.test;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FlagEvaluationDetails;

@Path("/flags")
public class GoFeatureFlagDevModeResource {
    @Inject
    Client client;

    @GET
    @Path("/string/{key}")
    public String stringFlag(@PathParam("key") String key) {
        return client.getStringValue(key, "default");
    }

    // temporary diagnostics for `GoFeatureFlagDevModeTest`

    @ConfigProperty(name = "quarkus.openfeature.gofeatureflag.url")
    String url;

    @GET
    @Path("/debug/{key}")
    public String debug(@PathParam("key") String key) {
        FlagEvaluationDetails<String> details = client.getStringDetails(key, "default");
        return "url=" + url
                + "\nvalue=" + details.getValue()
                + "\nvariant=" + details.getVariant()
                + "\nreason=" + details.getReason()
                + "\nerrorCode=" + details.getErrorCode()
                + "\nerrorMessage=" + details.getErrorMessage();
    }
}
