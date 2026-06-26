package io.quarkiverse.openfeature.it.unleash;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class UnleashResourceTest {
    @Test
    void booleanFlag() {
        when().get("/unleash/boolean/bool-flag")
                .then()
                .statusCode(200)
                .body(is("true"));
    }

    @Test
    void stringFlag() {
        when().get("/unleash/string/string-flag")
                .then()
                .statusCode(200)
                .body(is("hello"));
    }

    @Test
    void integerFlag() {
        when().get("/unleash/integer/int-flag")
                .then()
                .statusCode(200)
                .body(is("10"));
    }

    @Test
    void disabledFlagReturnsDefault() {
        when().get("/unleash/boolean/disabled-flag")
                .then()
                .statusCode(200)
                .body(is("false"));
    }

    @Test
    void missingFlagReturnsDefault() {
        when().get("/unleash/string/missing-flag")
                .then()
                .statusCode(200)
                .body(is("default"));
    }

    @Test
    void targetedFlagWithMatchingContext() {
        given().queryParam("targetingKey", "test-user")
                .when().get("/unleash/targeted/string/targeted-flag")
                .then()
                .statusCode(200)
                .body(is("targeted-value"));
    }

    @Test
    void targetedFlagWithNonMatchingContext() {
        given().queryParam("targetingKey", "other-user")
                .when().get("/unleash/targeted/string/targeted-flag")
                .then()
                .statusCode(200)
                .body(is("default"));
    }
}
