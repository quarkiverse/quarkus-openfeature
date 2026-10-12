package io.quarkiverse.openfeature.flipt.test;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;

/**
 * Container sharing only happens in dev mode, so this has to be a dev mode test. The Flipt
 * container below stands in for one started by another application; Dev Services must discover
 * it by its label instead of starting a second one.
 */
public class FliptSharedDevServiceTest {
    // TODO Quarkus 3.33 doesn't configure RestAssured for random ports in dev mode tests
    //  unique fixed ports work around parallel build conflicts until then
    private static final int PORT = 18180;

    private static final int FLIPT_PORT = 8080;

    private static final GenericContainer<?> sharedFlipt = new GenericContainer<>(
            DockerImageName.parse("flipt/flipt:v2"))
            .withLabel("quarkus-dev-service-flipt", "flipt")
            .withExposedPorts(FLIPT_PORT)
            .waitingFor(Wait.forHttp("/").forPort(FLIPT_PORT).forStatusCode(200));

    static {
        // must happen before the dev mode application is augmented
        sharedFlipt.start();
    }

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource(new StringAsset("quarkus.http.port=" + PORT + "\n"
                        + "quarkus.openfeature.await-providers=false\n"), "application.properties");
                root.addClass(FliptUrlResource.class);
            });

    @BeforeAll
    static void setPort() {
        RestAssured.port = PORT;
    }

    @AfterAll
    static void stopSharedFlipt() {
        sharedFlipt.stop();
    }

    @Test
    void sharedContainerIsUsed() {
        when().get("/flipt-url")
                .then()
                .statusCode(200)
                .body(is("http://" + sharedFlipt.getHost() + ":" + sharedFlipt.getMappedPort(FLIPT_PORT)));
    }
}
