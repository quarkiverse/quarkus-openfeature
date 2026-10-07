package io.quarkiverse.openfeature.flagd.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.Value;

public class SyncMetadataHookTest {
    // the hook ignores its `HookContext` and hints entirely,
    // so they are passed as `null` / empty map here
    private static Optional<EvaluationContext> before(SyncMetadataHook hook) {
        return hook.before(null, Map.of());
    }

    @Test
    public void suppliedContextIsReturned() {
        EvaluationContext context = new ImmutableContext(Map.of("scope", new Value("prod")));
        SyncMetadataHook hook = new SyncMetadataHook(() -> context);

        Optional<EvaluationContext> result = before(hook);

        assertTrue(result.isPresent());
        assertSame(context, result.get());
    }

    @Test
    public void noContextYieldsEmpty() {
        SyncMetadataHook hook = new SyncMetadataHook(() -> null);

        assertTrue(before(hook).isEmpty());
    }

    @Test
    public void theSupplierIsConsultedOnEveryEvaluation() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<EvaluationContext> current = new AtomicReference<>();
        SyncMetadataHook hook = new SyncMetadataHook(() -> {
            calls.incrementAndGet();
            return current.get();
        });

        assertTrue(before(hook).isEmpty());

        current.set(new ImmutableContext(Map.of("scope", new Value("staging"))));
        assertEquals("staging", before(hook).orElseThrow().getValue("scope").asString());

        current.set(new ImmutableContext(Map.of("scope", new Value("prod"))));
        assertEquals("prod", before(hook).orElseThrow().getValue("scope").asString());

        assertEquals(3, calls.get());
    }
}
