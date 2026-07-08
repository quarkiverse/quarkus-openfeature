package io.quarkiverse.openfeature.unleash.test;

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

public class UnleashDevModeTest {
    // TODO Quarkus 3.33 doesn't configure RestAssured for random ports in dev mode tests
    // (fixed in 3.38+: "Set RESTAssured local base URI in QuarkusDevModeTest");
    // fixed ports (unique per module) work around parallel build conflicts until then
    private static final int PORT = 18380;

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource("unleash.json");
                root.addAsResource(new StringAsset("quarkus.http.port=" + PORT), "application.properties");
                root.addClass(UnleashDevModeResource.class);
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
                .body(is("hello"));

        test.modifyResourceFile("unleash.json",
                s -> s.replace("\"value\": \"hello\"", "\"value\": \"goodbye\""));

        // the Unleash container restarts on reload, so the provider
        // may not be ready immediately after the dev mode restart
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            when().get("/flags/string/string-flag")
                    .then()
                    .statusCode(200)
                    .body(is("goodbye"));
        });
    }
}
