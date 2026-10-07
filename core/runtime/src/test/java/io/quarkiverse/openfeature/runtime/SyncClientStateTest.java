package io.quarkiverse.openfeature.runtime;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.GeneralError;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

public class SyncClientStateTest {
    private static final long TIMEOUT_SECONDS = 10;

    // INITIAL_DELAY_MS is 1000, so this is long enough for a reconnect that was
    // scheduled to have fired, and short enough to keep the negative tests quick
    private static final long NO_RECONNECT_MS = 1500;

    private Vertx vertx;
    private Context context;
    private ExecutorService executor;
    private SyncClientState state;

    @BeforeEach
    public void setUp() {
        vertx = Vertx.vertx();
        context = vertx.getOrCreateContext();
        executor = Executors.newCachedThreadPool();
        state = new SyncClientState(vertx);
    }

    @AfterEach
    public void tearDown() {
        executor.shutdownNow();
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    // states

    @Test
    public void initialState() {
        assertFalse(state.wasEverReady());
        assertFalse(state.isError());
        assertFalse(state.isShutdown());
    }

    @Test
    public void connectingToReady() throws Exception {
        state.setReady();

        assertTrue(state.wasEverReady());
        assertFalse(state.isError());
        state.awaitInitialized();
    }

    @Test
    public void connectingToInitialError() {
        state.setError();

        // INITIAL_ERROR, not ERROR: we have never been connected
        assertTrue(state.isError());
        assertFalse(state.wasEverReady());
    }

    @Test
    public void initialErrorStaysInitialError() {
        state.setError();
        state.setError();

        // a second failed attempt must not promote INITIAL_ERROR to ERROR,
        // which would make wasEverReady() claim a connection we never had
        assertTrue(state.isError());
        assertFalse(state.wasEverReady());
    }

    @Test
    public void initialErrorToReady() {
        state.setError();
        state.setReady();

        assertFalse(state.isError());
        assertTrue(state.wasEverReady());
    }

    @Test
    public void readyToError() {
        state.setReady();
        state.setError();

        // ERROR, not INITIAL_ERROR: we were connected once
        assertTrue(state.isError());
        assertTrue(state.wasEverReady());

        state.setError();
        assertTrue(state.isError());
        assertTrue(state.wasEverReady());
    }

    @Test
    public void errorToReady() {
        state.setReady();
        state.setError();
        state.setReady();

        assertFalse(state.isError());
        assertTrue(state.wasEverReady());
    }

    @Test
    public void shutdownIsTerminal() {
        state.setShutdown();

        state.setReady();
        state.setError();

        assertTrue(state.isShutdown());
        assertFalse(state.isError());
        assertFalse(state.wasEverReady());
    }

    /**
     * The reason {@code setReady} and {@code setError} use {@code getAndUpdate} rather than a
     * check followed by a {@code set}: they run on the Vert.x event loop while {@code shutdown()}
     * runs on whatever thread closes the application, so a stream callback can be in flight when
     * shutdown lands. Once SHUTDOWN is observed, no update may move the state back out of it.
     */
    @Test
    public void shutdownIsNeverLostToConcurrentUpdates() throws Exception {
        // each round gets one shutdown, so one chance to lose the race; the window is a
        // handful of instructions wide, which is why this needs many rounds to be reliable
        int rounds = 500;
        int threads = 4;

        for (int round = 0; round < rounds; round++) {
            SyncClientState state = new SyncClientState(vertx);
            AtomicInteger running = new AtomicInteger();
            AtomicBoolean stop = new AtomicBoolean();

            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                boolean ready = i % 2 == 0;
                workers.add(executor.submit(() -> {
                    running.incrementAndGet();
                    while (!stop.get()) {
                        if (ready) {
                            state.setReady();
                        } else {
                            state.setError();
                        }
                    }
                    return null;
                }));
            }

            // shut down while the workers are mid-flight, not before they start
            while (running.get() < threads) {
                Thread.onSpinWait();
            }
            state.setShutdown();
            stop.set(true);
            for (Future<?> worker : workers) {
                worker.get(TIMEOUT_SECONDS, SECONDS);
            }

            assertTrue(state.isShutdown(),
                    "round " + round + ": a concurrent setReady/setError moved the state out of SHUTDOWN");
        }
    }

    // barrier

    @Test
    public void awaitInitializedBlocksUntilReady() throws Exception {
        Future<?> awaiting = awaitInitializedOnAnotherThread();

        assertThrows(TimeoutException.class, () -> awaiting.get(100, MILLISECONDS));

        state.setReady();
        awaiting.get(TIMEOUT_SECONDS, SECONDS);
    }

    @Test
    public void awaitInitializedBlocksUntilError() throws Exception {
        Future<?> awaiting = awaitInitializedOnAnotherThread();

        assertThrows(TimeoutException.class, () -> awaiting.get(100, MILLISECONDS));

        state.setError();
        ExecutionException e = assertThrows(ExecutionException.class, () -> awaiting.get(TIMEOUT_SECONDS, SECONDS));
        assertInstanceOf(GeneralError.class, e.getCause());
    }

    @Test
    public void awaitInitializedBlocksUntilShutdown() throws Exception {
        Future<?> awaiting = awaitInitializedOnAnotherThread();

        assertThrows(TimeoutException.class, () -> awaiting.get(100, MILLISECONDS));

        state.setShutdown();
        ExecutionException e = assertThrows(ExecutionException.class, () -> awaiting.get(TIMEOUT_SECONDS, SECONDS));
        assertInstanceOf(FatalError.class, e.getCause());
    }

    @Test
    public void awaitInitializedAfterErrorFailsEvenOnceReconnected() throws Exception {
        state.setError();
        state.setReady();

        // the barrier only releases once; a late READY does not retroactively
        // turn a failed initialization into a successful one
        state.awaitInitialized();
    }

    private Future<?> awaitInitializedOnAnotherThread() {
        return executor.submit(() -> {
            state.awaitInitialized();
            return null;
        });
    }

    // backoff

    @Test
    public void nextDelayDoublesUpToTheCap() {
        assertEquals(2000, SyncClientState.nextDelay(1000));
        assertEquals(4000, SyncClientState.nextDelay(2000));
        assertEquals(8000, SyncClientState.nextDelay(4000));
        assertEquals(16_000, SyncClientState.nextDelay(8000));
        // doubling 16s would overshoot the cap
        assertEquals(30_000, SyncClientState.nextDelay(16_000));
        assertEquals(30_000, SyncClientState.nextDelay(30_000));
    }

    @Test
    public void successiveReconnectsWaitLonger() throws Exception {
        BlockingQueue<Long> fired = new LinkedBlockingQueue<>();
        Runnable action = () -> {
            fired.add(System.nanoTime());
        };

        long scheduledAt = System.nanoTime();
        onEventLoop(() -> state.scheduleReconnect(action));
        Long first = fired.poll(TIMEOUT_SECONDS, SECONDS);
        assertNotNull(first, "the first reconnect never fired");

        // the timer has fired, so a new reconnect can be scheduled
        onEventLoop(() -> state.scheduleReconnect(action));
        Long second = fired.poll(TIMEOUT_SECONDS, SECONDS);
        assertNotNull(second, "the second reconnect never fired");

        long firstDelay = (first - scheduledAt) / 1_000_000;
        long secondDelay = (second - first) / 1_000_000;
        assertTrue(firstDelay >= 900, "first reconnect waited only " + firstDelay + " ms");
        assertTrue(secondDelay >= 1800, "second reconnect waited only " + secondDelay + " ms");
    }

    @Test
    public void resetReconnectDelayGoesBackToTheInitialDelay() {
        onEventLoop(() -> {
            state.scheduleReconnect(() -> {
            });
            state.cancelReconnect();
            state.scheduleReconnect(() -> {
            });
            state.cancelReconnect();
        });
        assertEquals(4000, state.reconnectDelay());

        onEventLoop(() -> state.resetReconnectDelay());
        assertEquals(1000, state.reconnectDelay());
    }

    @Test
    public void cancelledReconnectDoesNotRun() throws Exception {
        CountDownLatch reconnected = new CountDownLatch(1);
        onEventLoop(() -> {
            state.scheduleReconnect(() -> {
                reconnected.countDown();
            });
            state.cancelReconnect();
        });

        assertFalse(reconnected.await(NO_RECONNECT_MS, MILLISECONDS), "a cancelled reconnect still ran");
    }

    @Test
    public void reconnectIsNotScheduledTwice() throws Exception {
        AtomicInteger reconnects = new AtomicInteger();
        Runnable action = () -> {
            reconnects.incrementAndGet();
        };
        onEventLoop(() -> {
            state.scheduleReconnect(action);
            state.scheduleReconnect(action);
        });

        // a second timer, had one been started, would have been given the next delay of
        // 2000 ms, so waiting out only the initial 1000 ms would not notice it
        Thread.sleep(2500);
        assertEquals(1, reconnects.get());
    }

    @Test
    public void reconnectIsNotScheduledAfterShutdown() throws Exception {
        CountDownLatch reconnected = new CountDownLatch(1);
        state.setShutdown();
        onEventLoop(() -> state.scheduleReconnect(() -> {
            reconnected.countDown();
        }));

        assertFalse(reconnected.await(NO_RECONNECT_MS, MILLISECONDS), "a reconnect ran after shutdown");
    }

    /**
     * {@code scheduleReconnect}, {@code cancelReconnect} and {@code resetReconnectDelay} touch
     * unsynchronized fields and are only legal on the event loop, so the tests call them there too.
     */
    private void onEventLoop(Runnable action) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(v -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.orTimeout(TIMEOUT_SECONDS, SECONDS).join();
    }
}
