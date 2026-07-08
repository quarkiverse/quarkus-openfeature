package io.quarkiverse.openfeature.flagd.test;

import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;

public class FlagdMultiDomainDevModeTest {
    // TODO Quarkus 3.33 doesn't configure RestAssured for random ports in dev mode tests
    // (fixed in 3.38+: "Set RESTAssured local base URI in QuarkusDevModeTest");
    // fixed ports (unique per module) work around parallel build conflicts until then
    private static final int PORT = 18080;

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource("flags.json");
                root.addAsResource("custom-flags.json");
                root.addAsResource("multi-domain-application.properties", "application.properties");
                root.addClass(FlagdMultiDomainDevModeResource.class);
            });

    @BeforeAll
    static void setPort() {
        RestAssured.port = PORT;
    }

    @Test
    void defaultDomainFlag() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            when().get("/flags/default/string/string-flag")
                    .then()
                    .statusCode(200)
                    .body(is("hello from flagd"));
        });
    }

    @Test
    void customDomainFlag() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            when().get("/flags/custom/string/custom-string-flag")
                    .then()
                    .statusCode(200)
                    .body(is("hello from custom domain"));
        });
    }

    @Test
    void domainsAreIsolated() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            when().get("/flags/custom/string/string-flag")
                    .then()
                    .statusCode(200)
                    .body(is("fallback"));
        });
    }
}
