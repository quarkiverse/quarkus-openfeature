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
    public void freshDataWithinGracePeriodSuppressesError() throws InterruptedException {
        syncState.setReady();

        // no reconnect happens here: the stream stays alive, one flag document just failed
        // to parse and the next one succeeded
        provider.expectEvents(2);
        provider.handleError("failed to process flag data");
        provider.handleConfigurationChanged("flags updated", List.of("flag"));
        provider.awaitEvents();

        // wait out the grace period to make sure no delayed error arrives
        Thread.sleep(10 * GRACE_PERIOD.toMillis());

        assertEquals(2, provider.events.size());
        assertEquals(ProviderEvent.PROVIDER_STALE, provider.events.get(0).type());

        Event ready = provider.events.get(1);
        assertEquals(ProviderEvent.PROVIDER_READY, ready.type());
        assertEquals(List.of("flag"), ready.details().getFlagsChanged());
    }

    @Test
    public void configurationChangedOutsideGracePeriodStaysConfigurationChanged() throws InterruptedException {
        syncState.setReady();

        provider.expectEvents(1);
        provider.handleConfigurationChanged("flags updated", List.of("flag"));
        provider.awaitEvents();

        assertEquals(1, provider.events.size());
        Event event = provider.events.get(0);
        assertEquals(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, event.type());
        assertEquals("flags updated", event.details().getMessage());
        assertEquals(List.of("flag"), event.details().getFlagsChanged());
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

    @Test
    public void eventLogKeepsTheHundredNewestEvents() {
        for (int i = 0; i < 250; i++) {
            provider.handleConfigurationChanged("event " + i);
        }

        List<DevFeatureAccess.EventInfo> log = provider.getEventLog();
        assertEquals(100, log.size());
        assertEquals("event 150", log.get(0).message());
        assertEquals("event 249", log.get(99).message());
    }

    @Test
    public void eventLogStaysBoundedUnderConcurrentRecording() throws InterruptedException {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        provider.handleConfigurationChanged(threadId + "-" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "recording threads did not finish");

        assertEquals(100, provider.getEventLog().size());
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
