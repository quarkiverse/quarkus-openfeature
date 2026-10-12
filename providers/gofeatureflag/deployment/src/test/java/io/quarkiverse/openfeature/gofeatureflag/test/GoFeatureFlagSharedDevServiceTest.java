package io.quarkiverse.openfeature.gofeatureflag.test;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;

/**
 * Container sharing only happens in dev mode, so this has to be a dev mode test. The GO Feature
 * Flag container below stands in for one started by another application; Dev Services must
 * discover it by its label instead of starting a second one.
 */
public class GoFeatureFlagSharedDevServiceTest {
    // TODO Quarkus 3.33 doesn't configure RestAssured for random ports in dev mode tests
    //  unique fixed ports work around parallel build conflicts until then
    private static final int PORT = 18280;

    private static final int HTTP_PORT = 1031;

    private static final GenericContainer<?> sharedGoFeatureFlag = new GenericContainer<>(
            DockerImageName.parse("gofeatureflag/go-feature-flag:latest"))
            .withLabel("quarkus-dev-service-gofeatureflag", "gofeatureflag")
            .withExposedPorts(HTTP_PORT)
            .withCopyToContainer(Transferable.of("""
                    pollingInterval: 1000
                    retrievers:
                      - kind: file
                        path: /goff/flags.goff.yaml
                    """), "/goff/goff-proxy.yaml")
            .withCopyToContainer(Transferable.of("""
                    bool-flag:
                      variations:
                        enabled: true
                        disabled: false
                      defaultRule:
                        variation: enabled
                    """), "/goff/flags.goff.yaml")
            .withCommand("/go-feature-flag", "--config", "/goff/goff-proxy.yaml")
            .waitingFor(Wait.forHttp("/health").forPort(HTTP_PORT).forStatusCode(200));

    static {
        // must happen before the dev mode application is augmented
        sharedGoFeatureFlag.start();
    }

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource(new StringAsset("quarkus.http.port=" + PORT + "\n"
                        + "quarkus.openfeature.await-providers=false\n"), "application.properties");
                root.addClass(GoFeatureFlagUrlResource.class);
            });

    @BeforeAll
    static void setPort() {
        RestAssured.port = PORT;
    }

    @AfterAll
    static void stopSharedGoFeatureFlag() {
        sharedGoFeatureFlag.stop();
    }

    @Test
    void sharedContainerIsUsed() {
        when().get("/gofeatureflag-url")
                .then()
                .statusCode(200)
                .body(is("http://" + sharedGoFeatureFlag.getHost() + ":"
                        + sharedGoFeatureFlag.getMappedPort(HTTP_PORT)));
    }
}
