package io.quarkiverse.openfeature.flipt.test;

import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

import java.time.Duration;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;

public class FliptDevModeTest {
    // dev mode tests set `quarkus.http.port` to 8080, which would prevent parallel tests
    // hence a fixed port, unique per module
    private static final int PORT = 18180;

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource("features.yaml");
                root.addAsResource(new StringAsset("quarkus.http.port=" + PORT), "application.properties");
                root.addClass(FliptDevModeResource.class);
            });

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
