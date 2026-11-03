package io.quarkiverse.openfeature.gofeatureflag.test;

import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;

public class GoFeatureFlagDevModeTest {
    // dev mode tests set `quarkus.http.port` to 8080, which would prevent parallel tests
    // hence a fixed port, unique per module
    private static final int PORT = 18280;

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource("flags.goff.yaml");
                root.addAsResource(new StringAsset("quarkus.http.port=" + PORT), "application.properties");
                root.addClass(GoFeatureFlagDevModeResource.class);
            });

    @Test
    void flagFileChange() {
        when().get("/flags/string/string-flag")
                .then()
                .statusCode(200)
                .body(is("hello"));

        test.modifyResourceFile("flags.goff.yaml",
                s -> s.replace("variation: greeting", "variation: parting"));

        // the relay proxy's file retriever polls every 1 second, so the updated flags
        // may not be available immediately after the dev mode restart
        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                when().get("/flags/string/string-flag")
                        .then()
                        .statusCode(200)
                        .body(is("goodbye"));
            });
        } catch (Throwable e) {
            dumpDiagnostics();
            throw e;
        }
    }

    private void dumpDiagnostics() {
        String debug = when().get("/flags/debug/string-flag").then().extract().asString();
        System.out.println("=== GO Feature Flag dev mode diagnostics ===");
        System.out.println(debug);

        String url = null;
        for (String line : debug.split("\n")) {
            if (line.startsWith("url=")) {
                url = line.substring("url=".length());
            }
        }
        if (url == null) {
            System.out.println("no relay proxy URL reported, cannot query the proxy");
            return;
        }

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/v1/flag/configuration"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("proxy configuration (" + response.statusCode() + "): " + response.body());
        } catch (Exception e) {
            System.out.println("failed to query the relay proxy: " + e);
        }
    }
}
