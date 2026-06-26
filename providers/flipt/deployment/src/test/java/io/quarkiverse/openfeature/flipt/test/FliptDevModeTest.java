package io.quarkiverse.openfeature.flipt.test;

import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;

public class FliptDevModeTest {
    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource("features.yaml");
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
