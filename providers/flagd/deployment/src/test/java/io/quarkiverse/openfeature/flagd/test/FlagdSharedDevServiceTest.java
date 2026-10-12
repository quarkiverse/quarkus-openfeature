package io.quarkiverse.openfeature.flagd.test;

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
 * Container sharing only happens in dev mode, so this has to be a dev mode test. The flagd
 * container below stands in for one started by another application; Dev Services must discover
 * it by its label instead of starting a second one.
 */
public class FlagdSharedDevServiceTest {
    // TODO Quarkus 3.33 doesn't configure RestAssured for random ports in dev mode tests
    //  unique fixed ports work around parallel build conflicts until then
    private static final int PORT = 18080;

    private static final int SYNC_PORT = 8015;

    private static final GenericContainer<?> sharedFlagd = new GenericContainer<>(
            DockerImageName.parse("ghcr.io/open-feature/flagd:latest"))
            .withLabel("quarkus-dev-service-flagd", "flagd")
            .withExposedPorts(SYNC_PORT)
            .withCommand("start", "-f", "file:/dev/null")
            .waitingFor(Wait.forLogMessage(".*starting flag sync service.*", 1));

    static {
        // must happen before the dev mode application is augmented
        sharedFlagd.start();
    }

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource(new StringAsset("quarkus.http.port=" + PORT + "\n"
                        + "quarkus.openfeature.await-providers=false\n"), "application.properties");
                root.addClass(FlagdUrlResource.class);
            });

    @BeforeAll
    static void setPort() {
        RestAssured.port = PORT;
    }

    @AfterAll
    static void stopSharedFlagd() {
        sharedFlagd.stop();
    }

    @Test
    void sharedContainerIsUsed() {
        when().get("/flagd-url")
                .then()
                .statusCode(200)
                .body(is(sharedFlagd.getHost() + ":" + sharedFlagd.getMappedPort(SYNC_PORT)));
    }
}
