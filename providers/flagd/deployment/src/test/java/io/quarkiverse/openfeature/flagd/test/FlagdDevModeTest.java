package io.quarkiverse.openfeature.flagd.test;

import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

import java.time.Duration;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;

public class FlagdDevModeTest {
    // TODO Quarkus 3.33 doesn't configure RestAssured for random ports in dev mode tests
    // (fixed in 3.38+: "Set RESTAssured local base URI in QuarkusDevModeTest");
    // fixed ports (unique per module) work around parallel build conflicts until then
    private static final int PORT = 18080;

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource("flags.json");
                root.addAsResource(new StringAsset("quarkus.http.port=" + PORT), "application.properties");
                root.addClass(FlagdDevModeResource.class);
            });

    @BeforeAll
    static void setPort() {
        RestAssured.port = PORT;
    }

    @Test
    void flagFileChange() {
        when().get("/flags/string/string-flag")
                .then()
                .statusCode(200)
                .body(is("hello from flagd"));

        test.modifyResourceFile("flags.json",
                s -> s.replace("\"defaultVariant\": \"greeting\"", "\"defaultVariant\": \"parting\""));

        // flagd's file watcher polls every 1 second, so the updated flags
        // may not be available immediately after the dev mode restart
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            when().get("/flags/string/string-flag")
                    .then()
                    .statusCode(200)
                    .body(is("goodbye"));
        });
    }
}
