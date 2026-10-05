package io.quarkiverse.openfeature.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.openfeature.sdk.Awaitable;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.Value;
import io.vertx.core.Vertx;

public class RemoteFeatureProviderEventsTest {
    private static final Duration GRACE_PERIOD = Duration.ofMillis(100);
    private static final long TIMEOUT_SECONDS = 10;

    private Vertx vertx;
    private SyncClientState syncState;
    private TestProvider provider;

    @BeforeEach
    public void setUp() {
        vertx = Vertx.vertx();
        syncState = new SyncClientState(vertx);
        provider = new TestProvider(vertx, GRACE_PERIOD, syncState);
    }

    @AfterEach
    public void tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    public void errorWhenNeverReadyIsProviderNotReady() throws InterruptedException {
        provider.expectEvents(1);
        provider.handleError("connection refused");
        provider.awaitEvents();

        assertEquals(1, provider.events.size());
        Event event = provider.events.get(0);
        assertEquals(ProviderEvent.PROVIDER_ERROR, event.type());
        assertEquals(ErrorCode.PROVIDER_NOT_READY, event.details().getErrorCode());
        assertEquals("connection refused", event.details().getMessage());
    }

    @Test
    public void errorAfterReadyIsStaleThenGeneralError() throws InterruptedException {
        syncState.setReady();

        provider.expectEvents(2);
        provider.handleError("stream broken");
        provider.awaitEvents();

        assertEquals(2, provider.events.size());

        Event stale = provider.events.get(0);
        assertEquals(ProviderEvent.PROVIDER_STALE, stale.type());
        assertNull(stale.details().getErrorCode());
        assertEquals("stream broken", stale.details().getMessage());

        Event error = provider.events.get(1);
        assertEquals(ProviderEvent.PROVIDER_ERROR, error.type());
        assertEquals(ErrorCode.GENERAL, error.details().getErrorCode());
        assertEquals("stream broken", error.details().getMessage());
    }

    @Test
    public void reconnectWithinGracePeriodSuppressesError() throws InterruptedException {
        syncState.setReady();

        provider.expectEvents(2);
        provider.handleError("stream broken");
        provider.handleReconnected();
        provider.awaitEvents();

        // wait out the grace period to make sure no delayed error arrives
        Thread.sleep(10 * GRACE_PERIOD.toMillis());

        assertEquals(2, provider.events.size());
        assertEquals(ProviderEvent.PROVIDER_STALE, provider.events.get(0).type());
        assertEquals(ProviderEvent.PROVIDER_READY, provider.events.get(1).type());
    }

    @Test
    public void fatalErrorIsProviderFatal() throws InterruptedException {
        syncState.setReady();

        provider.expectEvents(1);
        provider.handleFatalError("unauthenticated");
        provider.awaitEvents();

        assertEquals(1, provider.events.size());
        Event event = provider.events.get(0);
        assertEquals(ProviderEvent.PROVIDER_ERROR, event.type());
        assertEquals(ErrorCode.PROVIDER_FATAL, event.details().getErrorCode());
        assertEquals("unauthenticated", event.details().getMessage());
    }

    private record Event(ProviderEvent type, ProviderEventDetails details) {
    }

    private static final class TestProvider extends AbstractRemoteFeatureProvider {
        final List<Event> events = new CopyOnWriteArrayList<>();

        private volatile CountDownLatch latch = new CountDownLatch(0);

        TestProvider(Vertx vertx, Duration gracePeriod, SyncClientState syncState) {
            super(vertx, gracePeriod, syncState);
        }

        void expectEvents(int count) {
            latch = new CountDownLatch(count);
        }

        void awaitEvents() throws InterruptedException {
            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "expected events were not emitted");
        }

        @Override
        public Awaitable emit(ProviderEvent event, ProviderEventDetails details) {
            events.add(new Event(event, details));
            latch.countDown();
            return Awaitable.FINISHED;
        }

        @Override
        public Metadata getMetadata() {
            return () -> "test";
        }

        @Override
        protected void doShutdown() {
        }

        @Override
        public Collection<FlagInfo> getFlags() {
            return List.of();
        }

        // this test only exercises lifecycle events, never evaluation

        @Override
        public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
            throw new UnsupportedOperationException();
        }
    }
}
