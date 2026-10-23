package io.quarkiverse.openfeature.flagd.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.ProviderState;
import io.quarkiverse.openfeature.deployment.OpenFeatureProviderBuildItem;
import io.quarkiverse.openfeature.test.runtime.Disconnect;
import io.quarkiverse.openfeature.test.runtime.FlagdTestContainer;
import io.quarkiverse.openfeature.test.runtime.Reconnect;
import io.quarkiverse.openfeature.test.runtime.TcpProxyExtension;
import io.quarkus.test.QuarkusUnitTest;

/**
 * A known gap, kept as an executable description of it: a domain with more than one provider
 * reports no lifecycle events at all, so it looks {@code READY} through any outage.
 * <p>
 * Only the registered provider is attached to the SDK's event emitter, and for such a domain
 * that is the {@code MultiProvider}, which does not observe its children. The SDK has no public
 * seam to hook them either: {@code EventProvider.setEventProviderListener} and
 * {@code EventProvider.attach} are both package-private.
 * <p>
 * The fix belongs upstream, in
 * <a href="https://github.com/open-feature/java-sdk/pull/2004">java-sdk#2004</a>, which adds a
 * public {@code EventProvider.addEventObserver} and aggregates child state with "worst wins"
 * precedence. Enable this test once that ships and the SDK dependency is bumped. Note that the
 * event messages are asserted only loosely here, because their exact wording is up to the SDK.
 *
 * @see FlagdMultiProviderTest for what does work in a multi-provider domain
 */
@Disabled("multi-provider domains report no lifecycle events; blocked on java-sdk#2004")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FlagdMultiProviderEventsTest {
    private static final FlagdTestContainer flagd = new FlagdTestContainer.Builder("flags.json").build();

    private static final List<EventDetails> stales = new CopyOnWriteArrayList<>();
    private static final List<EventDetails> errors = new CopyOnWriteArrayList<>();

    @RegisterExtension
    @Order(1)
    static final TcpProxyExtension proxy = TcpProxyExtension.create(flagd, FlagdTestContainer.SYNC_PORT);

    @RegisterExtension
    @Order(2)
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addAsResource("flags.json").addClass(SecondProviderFactory.class))
            .addBuildChainCustomizer(builder -> {
                builder.addBuildStep(context -> {
                    context.produce(new OpenFeatureProviderBuildItem(SecondProviderFactory.NAME,
                            new SecondProviderFactory()));
                })
                        .produces(OpenFeatureProviderBuildItem.class)
                        .build();
            })
            .overrideConfigKey("quarkus.openfeature.provider", "flagd," + SecondProviderFactory.NAME);

    static {
        test.setBeforeAllCustomizer(() -> {
            test.overrideConfigKey("quarkus.openfeature.flagd.url", "localhost:" + proxy.port());
            test.overrideConfigKey("quarkus.openfeature.flagd.grace-period", "3s");
        });
    }

    @Inject
    Client client;

    @Test
    @Order(1)
    void providerIsReady() {
        assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);

        client.onProviderStale(stales::add);
        client.onProviderError(errors::add);
    }

    @Test
    @Order(2)
    @Disconnect
    void disconnect() {
    }

    // the flagd child going stale has to take the whole domain with it
    @Test
    @Order(3)
    void staleReachesTheDomain() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.STALE);
        });
        assertThat(stales).isNotEmpty();

        // the second provider still answers, which is the point of having two
        assertThat(client.getStringValue("second-provider-flag", "default")).isEqualTo("only in the second provider");
    }

    @Test
    @Order(4)
    void errorReachesTheDomain() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.ERROR);
        });
        assertThat(errors).isNotEmpty();
    }

    @Test
    @Order(5)
    @Reconnect
    void reconnect() {
    }

    @Test
    @Order(6)
    void readyReachesTheDomain() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(client.getProviderState()).isEqualTo(ProviderState.READY);
        });
        assertThat(client.getStringValue("string-flag", "default")).isEqualTo("hello from flagd");
        assertThat(client.getStringValue("second-provider-flag", "default")).isEqualTo("only in the second provider");
    }
}
