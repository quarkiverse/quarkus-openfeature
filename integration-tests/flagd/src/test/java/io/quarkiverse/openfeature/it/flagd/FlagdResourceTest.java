package io.quarkiverse.openfeature.it.flagd;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class FlagdResourceTest {
    @Test
    void booleanFlag() {
        when().get("/flagd/boolean/bool-flag")
                .then()
                .statusCode(200)
                .body(is("true"));
    }

    @Test
    void stringFlag() {
        when().get("/flagd/string/string-flag")
                .then()
                .statusCode(200)
                .body(is("hello from flagd"));
    }

    @Test
    void integerFlag() {
        when().get("/flagd/integer/int-flag")
                .then()
                .statusCode(200)
                .body(is("10"));
    }

    @Test
    void disabledFlagReturnsDefault() {
        when().get("/flagd/boolean/disabled-flag")
                .then()
                .statusCode(200)
                .body(is("false"));
    }

    @Test
    void missingFlagReturnsDefault() {
        when().get("/flagd/string/missing-flag")
                .then()
                .statusCode(200)
                .body(is("default"));
    }

    @Test
    void targetedFlagWithMatchingContext() {
        given().queryParam("targetingKey", "test-user")
                .when().get("/flagd/targeted/string/targeted-flag")
                .then()
                .statusCode(200)
                .body(is("targeted-value"));
    }

    @Test
    void targetedFlagWithNonMatchingContext() {
        given().queryParam("targetingKey", "other-user")
                .when().get("/flagd/targeted/string/targeted-flag")
                .then()
                .statusCode(200)
                .body(is("default-value"));
    }
}
