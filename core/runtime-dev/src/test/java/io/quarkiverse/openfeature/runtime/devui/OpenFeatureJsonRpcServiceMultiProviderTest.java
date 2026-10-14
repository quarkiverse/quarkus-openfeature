package io.quarkiverse.openfeature.runtime.devui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.openfeature.sdk.OpenFeatureAPI;
import io.quarkiverse.openfeature.runtime.ConfigFeatureProvider;
import io.quarkiverse.openfeature.runtime.OpenFeatureRecorder;
import io.quarkiverse.openfeature.runtime.OpenFeatureRuntimeConfig;
import io.quarkus.runtime.RuntimeValue;
import io.vertx.core.json.JsonObject;

/**
 * Dev UI overrides against a domain backed by a {@code MultiProvider}: the overrides are
 * pushed to the child providers, which is what {@link OpenFeatureRecorder#getProviders(String)}
 * holds, while evaluation goes through the {@code MultiProvider} wrapper.
 */
public class OpenFeatureJsonRpcServiceMultiProviderTest {
    private static final String DOMAIN = "multi-provider-domain";

    @BeforeAll
    static void setUpProviders() {
        OpenFeatureRuntimeConfig config = () -> OpenFeatureRuntimeConfig.AwaitProviders.TRUE;
        OpenFeatureRecorder recorder = new OpenFeatureRecorder(new RuntimeValue<>(config));
        recorder.configureProvider(DOMAIN, List.of(
                domain -> new ConfigFeatureProvider("first", Map.of("only-in-first", "first-value")),
                domain -> new ConfigFeatureProvider("second", Map.of("only-in-second", "second-value"))));
    }

    @AfterAll
    static void tearDownProviders() {
        OpenFeatureAPI.getInstance().shutdown();
    }

    private static String evaluateString(OpenFeatureJsonRpcService service, String key) {
        JsonObject result = service.evaluate(DOMAIN, key, "string", "fallback", null);
        return result.getString("value");
    }

    @Test
    void overrideAppliesToEveryChildProvider() {
        OpenFeatureJsonRpcService service = new OpenFeatureJsonRpcService();
        try {
            assertThat(evaluateString(service, "only-in-first")).isEqualTo("first-value");
            assertThat(evaluateString(service, "only-in-second")).isEqualTo("second-value");

            service.setOverride(DOMAIN, "only-in-first", "overridden-first", "string");
            service.setOverride(DOMAIN, "only-in-second", "overridden-second", "string");

            assertThat(evaluateString(service, "only-in-first")).isEqualTo("overridden-first");
            assertThat(evaluateString(service, "only-in-second")).isEqualTo("overridden-second");
        } finally {
            service.clearAllOverrides(DOMAIN);
        }
    }

    @Test
    void overrideOfAnUnknownFlagIsResolved() {
        OpenFeatureJsonRpcService service = new OpenFeatureJsonRpcService();
        try {
            assertThat(evaluateString(service, "nowhere")).isEqualTo("fallback");

            service.setOverride(DOMAIN, "nowhere", "invented", "string");

            assertThat(evaluateString(service, "nowhere")).isEqualTo("invented");
        } finally {
            service.clearAllOverrides(DOMAIN);
        }
    }

    @Test
    void clearingAnOverrideRestoresTheProviderValue() {
        OpenFeatureJsonRpcService service = new OpenFeatureJsonRpcService();
        try {
            service.setOverride(DOMAIN, "only-in-first", "overridden-first", "string");
            service.setOverride(DOMAIN, "only-in-second", "overridden-second", "string");

            service.clearOverride(DOMAIN, "only-in-first");

            assertThat(evaluateString(service, "only-in-first")).isEqualTo("first-value");
            assertThat(evaluateString(service, "only-in-second")).isEqualTo("overridden-second");

            service.clearAllOverrides(DOMAIN);

            assertThat(evaluateString(service, "only-in-second")).isEqualTo("second-value");
        } finally {
            service.clearAllOverrides(DOMAIN);
        }
    }
}
