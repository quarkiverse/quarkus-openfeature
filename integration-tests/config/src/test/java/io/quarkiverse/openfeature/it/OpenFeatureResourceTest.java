package io.quarkiverse.openfeature.it;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class OpenFeatureResourceTest {
    @Test
    void runtimeFlag() {
        when().get("/openfeature/default/runtime-flag")
                .then()
                .statusCode(200)
                .body(is("from-runtime"));
    }

    @Test
    void buildtimeFlag() {
        when().get("/openfeature/default/buildtime-flag")
                .then()
                .statusCode(200)
                .body(is("from-buildtime"));
    }

    @Test
    void runtimeWinsOverBuildtime() {
        when().get("/openfeature/default/overridden")
                .then()
                .statusCode(200)
                .body(is("runtime-wins"));
    }

    @Test
    void missingFlagReturnsDefault() {
        when().get("/openfeature/default/missing")
                .then()
                .statusCode(200)
                .body(is("default"));
    }

    @Test
    void customDomainFlag() {
        when().get("/openfeature/custom/custom-flag")
                .then()
                .statusCode(200)
                .body(is("custom-value"));
    }

    @Test
    void customDomainDoesNotSeeDefaultFlags() {
        when().get("/openfeature/custom/runtime-flag")
                .then()
                .statusCode(200)
                .body(is("default"));
    }
}
