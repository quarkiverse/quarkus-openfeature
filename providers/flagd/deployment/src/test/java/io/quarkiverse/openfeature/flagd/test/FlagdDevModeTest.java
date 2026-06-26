package io.quarkiverse.openfeature.flagd.test;

import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;

public class FlagdDevModeTest {
    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource("flags.json");
                root.addClass(FlagdDevModeResource.class);
            });

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
