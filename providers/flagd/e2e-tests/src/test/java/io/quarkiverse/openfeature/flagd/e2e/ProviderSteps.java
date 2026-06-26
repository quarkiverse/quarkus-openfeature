package io.quarkiverse.openfeature.flagd.e2e;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;

import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.wait.strategy.Wait;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.openfeature.contrib.tools.flagd.core.FlagdCore;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.quarkiverse.openfeature.flagd.runtime.FlagdConfig;
import io.quarkiverse.openfeature.flagd.runtime.FlagdFeatureProvider;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vertx.core.Vertx;

public class ProviderSteps {
    static final int MANAGEMENT_PORT = 8014;
    static final int SYNC_PORT = 8015;
    static final int LAUNCHPAD_PORT = 8080;

    static final int ENVOY_PROXY_PORT = 9211;
    static final int ENVOY_FORBIDDEN_PORT = 9212;

    static final int UNAVAILABLE_PORT = 277;

    static ComposeContainer container;
    static Vertx vertx;
    static HttpClient httpClient = HttpClient.newHttpClient();

    private final TestState state;

    public ProviderSteps(TestState state) {
        this.state = state;
    }

    @BeforeAll
    public static void beforeAll() throws IOException {
        Path sharedTempDir = Files.createTempDirectory(Paths.get("target"), "flagd-e2e-");
        container = new ComposeContainer(new File("src/test/resources/docker-compose.yaml"))
                .withEnv("FLAGS_DIR", sharedTempDir.toAbsolutePath().toString())
                .withExposedService("flagd", MANAGEMENT_PORT, Wait.forHttp("/healthz").forPort(MANAGEMENT_PORT))
                .withExposedService("flagd", SYNC_PORT, Wait.forListeningPort())
                .withExposedService("flagd", LAUNCHPAD_PORT, Wait.forListeningPort())
                .withExposedService("envoy", ENVOY_PROXY_PORT, Wait.forListeningPort())
                .withExposedService("envoy", ENVOY_FORBIDDEN_PORT, Wait.forListeningPort())
                .withStartupTimeout(Duration.ofSeconds(45));
        container.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    public static void afterAll() {
        if (container != null) {
            container.stop();
        }
        if (vertx != null) {
            vertx.close();
        }
    }

    @After
    public void tearDown() {
        if (state.client != null) {
            post(getLaunchpadUrl() + "/stop");
        }
        OpenFeatureAPI.getInstance().shutdown();
        state.client = null;
        state.context = null;
        state.evaluation = null;
        state.options.clear();
        state.events.clear();
        state.lastEvent = java.util.Optional.empty();
    }

    @Given("a {word} flagd provider")
    public void setupProvider(String providerType) throws InterruptedException {
        String url;
        boolean wait;
        String launchpadConfig = "default";

        switch (providerType) {
            case "unavailable" -> {
                url = "localhost:" + UNAVAILABLE_PORT;
                wait = false;
            }
            case "forbidden" -> {
                url = getFlagdHost() + ":" + container.getServicePort("envoy", ENVOY_FORBIDDEN_PORT);
                wait = false;
            }
            case "metadata" -> {
                launchpadConfig = "metadata";
                url = getSyncAddress();
                wait = true;
            }
            case "syncpayload" -> {
                launchpadConfig = "sync-payload";
                url = getSyncAddress();
                wait = true;
            }
            default -> {
                url = getSyncAddress();
                wait = true;
            }
        }

        post(getLaunchpadUrl() + "/start?config=" + launchpadConfig);
        Thread.sleep(50);

        FlagdConfig.ProviderConfig connectionConfig = createConnectionConfig(url);
        FlagdCore evaluator = new FlagdCore();
        FlagdFeatureProvider provider = new FlagdFeatureProvider(new ObjectMapper(), evaluator, vertx,
                connectionConfig, null);

        String providerName = "test-" + Math.random();
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        if (wait) {
            api.setProviderAndWait(providerName, provider);
        } else {
            api.setProvider(providerName, provider);
        }
        state.client = api.getClient(providerName);
    }

    @Given("an option {string} of type {string} with value {string}")
    public void anOption(String key, String type, String value) {
        state.options.put(key, value);
    }

    @When("the connection is lost")
    public void connectionLost() {
        post(getLaunchpadUrl() + "/stop");
    }

    @When("the connection is lost for {int}s")
    public void connectionLostFor(int seconds) {
        post(getLaunchpadUrl() + "/restart?seconds=" + seconds);
    }

    @When("the flag was modified")
    public void flagModified() {
        post(getLaunchpadUrl() + "/change");
    }

    private static String getFlagdHost() {
        return container.getContainerByServiceName("flagd")
                .map(ContainerState::getHost)
                .orElseThrow(() -> new RuntimeException("flagd service not found"));
    }

    private static String getSyncAddress() {
        Optional<ContainerState> flagd = container.getContainerByServiceName("flagd");
        return flagd.map(c -> c.getHost() + ":" + c.getMappedPort(SYNC_PORT))
                .orElseThrow(() -> new RuntimeException("flagd service not found"));
    }

    private static String getLaunchpadUrl() {
        Optional<ContainerState> flagd = container.getContainerByServiceName("flagd");
        return flagd.map(c -> "http://" + c.getHost() + ":" + c.getMappedPort(LAUNCHPAD_PORT))
                .orElseThrow(() -> new RuntimeException("flagd service not found"));
    }

    static void post(String url) {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Launchpad request failed: " + response.statusCode() + " " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Launchpad request failed", e);
        }
    }

    private FlagdConfig.ProviderConfig createConnectionConfig(String url) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
                .withMapping(FlagdConfig.ProviderConfig.class, "flagd")
                .withDefaultValue("flagd.url", url)
                .withDefaultValue("flagd.stream-deadline", "PT10M")
                .withDefaultValue("flagd.grace-period", "PT2S");
        String selector = state.options.get("selector");
        if (selector != null) {
            builder.withDefaultValue("flagd.selector", selector);
        }
        return builder.build().getConfigMapping(FlagdConfig.ProviderConfig.class, "flagd");
    }
}
