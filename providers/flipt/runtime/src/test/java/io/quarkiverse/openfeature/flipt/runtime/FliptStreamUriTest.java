package io.quarkiverse.openfeature.flipt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

public class FliptStreamUriTest {
    @Test
    void withoutReference() {
        assertEquals("/client/v2/environments/default/namespaces/default/stream",
                FliptSyncClient.streamUri("default", "default", Optional.empty()));
    }

    @Test
    void withReference() {
        // Flipt references are Git refs, where slashes are common
        assertEquals("/client/v2/environments/prod/namespaces/billing/stream?reference=feature%2Fnew-checkout",
                FliptSyncClient.streamUri("prod", "billing", Optional.of("feature/new-checkout")));
    }

    @Test
    void referenceIsUrlEncoded() {
        assertEquals("/client/v2/environments/default/namespaces/default/stream?reference=a+b%26c%3Dd",
                FliptSyncClient.streamUri("default", "default", Optional.of("a b&c=d")));
    }
}
