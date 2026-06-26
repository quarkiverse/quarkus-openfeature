package io.quarkiverse.openfeature.it.gofeatureflag;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class GoFeatureFlagResourceTest {
    @Test
    void booleanFlag() {
        when().get("/goff/boolean/bool-flag")
                .then()
                .statusCode(200)
                .body(is("true"));
    }

    @Test
    void stringFlag() {
        when().get("/goff/string/string-flag")
                .then()
                .statusCode(200)
                .body(is("hello"));
    }

    @Test
    void integerFlag() {
        when().get("/goff/integer/int-flag")
                .then()
                .statusCode(200)
                .body(is("10"));
    }

    @Test
    void doubleFlag() {
        when().get("/goff/double/double-flag")
                .then()
                .statusCode(200)
                .body(is("3.14"));
    }

    @Test
    void disabledFlagReturnsDefault() {
        when().get("/goff/boolean/disabled-flag")
                .then()
                .statusCode(200)
                .body(is("false"));
    }

    @Test
    void missingFlagReturnsDefault() {
        when().get("/goff/string/missing-flag")
                .then()
                .statusCode(200)
                .body(is("default"));
    }

    @Test
    void targetedFlagWithMatchingContext() {
        given().queryParam("targetingKey", "test-user")
                .when().get("/goff/targeted/string/targeted-flag")
                .then()
                .statusCode(200)
                .body(is("targeted-value"));
    }

    @Test
    void targetedFlagWithNonMatchingContext() {
        given().queryParam("targetingKey", "other-user")
                .when().get("/goff/targeted/string/targeted-flag")
                .then()
                .statusCode(200)
                .body(is("default-value"));
    }
}
