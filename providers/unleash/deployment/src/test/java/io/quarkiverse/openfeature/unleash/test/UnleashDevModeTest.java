package io.quarkiverse.openfeature.unleash.test;

import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;

public class UnleashDevModeTest {
    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource("unleash.json");
                root.addClass(UnleashDevModeResource.class);
            });

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
