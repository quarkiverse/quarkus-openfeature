package io.quarkiverse.openfeature.it.flipt;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class FliptResourceTest {
    @Test
    void booleanFlag() {
        when().get("/flipt/boolean/bool-flag")
                .then()
                .statusCode(200)
                .body(is("true"));
    }

    @Test
    void stringFlag() {
        when().get("/flipt/string/string-flag")
                .then()
                .statusCode(200)
                .body(is("greeting"));
    }

    @Test
    void integerFlag() {
        when().get("/flipt/integer/int-flag")
                .then()
                .statusCode(200)
                .body(is("10"));
    }

    @Test
    void disabledFlagReturnsDefault() {
        when().get("/flipt/boolean/disabled-flag")
                .then()
                .statusCode(200)
                .body(is("false"));
    }

    @Test
    void missingFlagReturnsDefault() {
        when().get("/flipt/string/missing-flag")
                .then()
                .statusCode(200)
                .body(is("default"));
    }

    @Test
    void targetedFlagWithMatchingContext() {
        given().queryParam("targetingKey", "test-user")
                .queryParam("userType", "tester")
                .when().get("/flipt/targeted/string/targeted-flag")
                .then()
                .statusCode(200)
                .body(is("targeted-value"));
    }

    @Test
    void targetedFlagWithNonMatchingContext() {
        given().queryParam("targetingKey", "test-user")
                .queryParam("userType", "regular")
                .when().get("/flipt/targeted/string/targeted-flag")
                .then()
                .statusCode(200)
                .body(is("default-value"));
    }
}
