package io.quarkiverse.openfeature.unleash.test;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;

/**
 * Container sharing only happens in dev mode, so this has to be a dev mode test. The Unleash
 * container below stands in for one started by another application; Dev Services must discover
 * it by its label instead of starting a second one. It needs its own database, just like the
 * container that {@code UnleashDevContainer} starts.
 */
public class UnleashSharedDevServiceTest {
    // TODO Quarkus 3.33 doesn't configure RestAssured for random ports in dev mode tests
    //  unique fixed ports work around parallel build conflicts until then
    private static final int PORT = 18380;

    private static final int HTTP_PORT = 4242;

    private static final Network network = Network.newNetwork();

    private static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:18"))
            .withNetwork(network)
            .withNetworkAliases("unleash-db")
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "unleash")
            .withEnv("POSTGRES_USER", "unleash")
            .withEnv("POSTGRES_PASSWORD", "unleash")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 1));

    private static final GenericContainer<?> sharedUnleash = new GenericContainer<>(
            DockerImageName.parse("unleashorg/unleash-server:latest"))
            .withLabel("quarkus-dev-service-unleash", "unleash")
            .withNetwork(network)
            .withExposedPorts(HTTP_PORT)
            .withEnv("DATABASE_URL", "postgres://unleash:unleash@unleash-db:5432/unleash")
            .withEnv("DATABASE_SSL", "false")
            .withEnv("CHECK_VERSION", "false")
            .withEnv("AUTH_TYPE", "none")
            .waitingFor(Wait.forHttp("/health").forPort(HTTP_PORT).forStatusCode(200));

    static {
        // must happen before the dev mode application is augmented
        postgres.start();
        sharedUnleash.start();
    }

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot(root -> {
                root.addAsResource(new StringAsset("quarkus.http.port=" + PORT + "\n"
                        + "quarkus.openfeature.await-providers=false\n"), "application.properties");
                root.addClass(UnleashUrlResource.class);
            });

    @BeforeAll
    static void setPort() {
        RestAssured.port = PORT;
    }

    @AfterAll
    static void stopSharedUnleash() {
        sharedUnleash.stop();
        postgres.stop();
        network.close();
    }

    @Test
    void sharedContainerIsUsed() {
        when().get("/unleash-url")
                .then()
                .statusCode(200)
                .body(is("http://" + sharedUnleash.getHost() + ":" + sharedUnleash.getMappedPort(HTTP_PORT) + "/api"));
    }
}
