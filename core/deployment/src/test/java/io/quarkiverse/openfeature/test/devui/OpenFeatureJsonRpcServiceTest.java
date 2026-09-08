package io.quarkiverse.openfeature.test.devui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.quarkiverse.openfeature.runtime.devui.OpenFeatureJsonRpcService;
import io.vertx.core.json.JsonObject;

public class OpenFeatureJsonRpcServiceTest {
    private static final String DOMAIN = "test-domain";

    private static JsonObject overridesOf(OpenFeatureJsonRpcService service, String domain) {
        return service.getOverrides(domain).getJsonObject("overrides");
    }

    @Test
    void setAndClearSingleOverride() {
        OpenFeatureJsonRpcService service = new OpenFeatureJsonRpcService();

        assertThat(overridesOf(service, DOMAIN)).isEmpty();

        assertThat(service.setOverride(DOMAIN, "my-flag", "true", "boolean").getBoolean("success")).isTrue();
        assertThat(overridesOf(service, DOMAIN).getString("my-flag")).isEqualTo("true");

        service.clearOverride(DOMAIN, "my-flag");
        assertThat(overridesOf(service, DOMAIN)).isEmpty();
    }

    @Test
    void clearAllRemovesEveryOverride() {
        OpenFeatureJsonRpcService service = new OpenFeatureJsonRpcService();
        service.setOverride(DOMAIN, "first", "1", "integer");
        service.setOverride(DOMAIN, "second", "2.5", "double");
        assertThat(overridesOf(service, DOMAIN)).hasSize(2);

        service.clearAllOverrides(DOMAIN);
        assertThat(overridesOf(service, DOMAIN)).isEmpty();
    }

    @Test
    void domainsAreIndependent() {
        OpenFeatureJsonRpcService service = new OpenFeatureJsonRpcService();
        service.setOverride("one", "my-flag", "true", "boolean");
        service.setOverride("two", "my-flag", "false", "boolean");

        assertThat(overridesOf(service, "one").getString("my-flag")).isEqualTo("true");
        assertThat(overridesOf(service, "two").getString("my-flag")).isEqualTo("false");

        service.clearAllOverrides("one");
        assertThat(overridesOf(service, "one")).isEmpty();
        assertThat(overridesOf(service, "two").getString("my-flag")).isEqualTo("false");
    }

    @Test
    void unparseableValueIsReportedAsError() {
        OpenFeatureJsonRpcService service = new OpenFeatureJsonRpcService();
        assertThat(service.setOverride(DOMAIN, "my-flag", "not-a-number", "integer").getString("error"))
                .isNotNull();
        assertThat(overridesOf(service, DOMAIN)).isEmpty();
    }

    @Test
    void unknownTypeIsReportedAsError() {
        OpenFeatureJsonRpcService service = new OpenFeatureJsonRpcService();
        assertThat(service.setOverride(DOMAIN, "my-flag", "x", "banana").getString("error"))
                .contains("banana");
    }

    @Test
    void concurrentSetOverrideKeepsEveryOverride() throws Exception {
        int threads = 8;
        int perThread = 100;
        OpenFeatureJsonRpcService service = new OpenFeatureJsonRpcService();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier start = new CyclicBarrier(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        service.setOverride(DOMAIN, "flag-" + thread + "-" + i, "true", "boolean");
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(overridesOf(service, DOMAIN)).hasSize(threads * perThread);
    }
}
