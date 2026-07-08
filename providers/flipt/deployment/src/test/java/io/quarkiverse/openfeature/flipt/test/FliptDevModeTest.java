package io.quarkiverse.openfeature.flipt.test;

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

public class FliptDevModeTest {
    // TODO Quarkus 3.33 doesn't configure RestAssured for random ports in dev mode tests
    // (fixed in 3.38+: "Set RESTAssured local base URI in QuarkusDevModeTest");
    // fixed ports (unique per module) work around parallel build conflicts until then
    private static final int PORT = 18180;

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource("features.yaml");
                root.addAsResource(new StringAsset("quarkus.http.port=" + PORT), "application.properties");
                root.addClass(FliptDevModeResource.class);
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
                .body(is("greeting"));

        test.modifyResourceFile("features.yaml",
                s -> s.replace("variant: greeting\n            rollout: 100",
                        "variant: parting\n            rollout: 100"));

        // the Flipt container restarts on reload, so the provider
        // may not be ready immediately after the dev mode restart
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            when().get("/flags/string/string-flag")
                    .then()
                    .statusCode(200)
                    .body(is("parting"));
        });
    }
}
