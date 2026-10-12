package io.quarkiverse.openfeature.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.logging.Logger;

import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.GeneralError;
import io.vertx.core.Vertx;

public final class SyncClientState {
    private static final Logger log = Logger.getLogger(SyncClientState.class);

    // +------------+     +-------+     +-------+
    // | CONNECTING |---->| READY |<--->| ERROR |
    // +-----+------+     +-------+     +-------+
    //       |                ^
    //       v                |
    // +---------------+      |                   +----------+
    // | INITIAL_ERROR |------+       from any -->| SHUTDOWN |
    // +---------------+                          +----------+

    private static final int CONNECTING = 0;
    private static final int READY = 1;
    private static final int ERROR = 2;
    private static final int INITIAL_ERROR = 3;
    private static final int SHUTDOWN = 4;

    private static final long INITIAL_DELAY_MS = 1000;
    private static final long MAX_DELAY_MS = 30_000;

    /**
     * How long {@link #awaitInitialized()} waits before giving up.
     * <p>
     * The provider request timeouts are generally 10 seconds, and we don't want
     * to preempt those. Hence, this has to stay above 10 seconds.
     */
    private static final long INITIALIZATION_TIMEOUT_MS = 15_000;

    static long nextDelay(long currentDelay) {
        return Math.min(currentDelay * 2, MAX_DELAY_MS);
    }

    private final AtomicInteger state = new AtomicInteger(CONNECTING);
    private final CountDownLatch initializedBarrier = new CountDownLatch(1);
    private final long initializationTimeoutMs;

    // only accessed from the Vert.x event loop, no synchronization needed
    private final Vertx vertx;
    private long reconnectTimerId = -1;
    private long reconnectDelay = INITIAL_DELAY_MS;

    public SyncClientState(Vertx vertx) {
        this(vertx, INITIALIZATION_TIMEOUT_MS);
    }

    // visible for tests
    SyncClientState(Vertx vertx, long initializationTimeoutMs) {
        this.vertx = vertx;
        this.initializationTimeoutMs = initializationTimeoutMs;
    }

    public void awaitInitialized() throws Exception {
        if (!initializedBarrier.await(initializationTimeoutMs, TimeUnit.MILLISECONDS)) {
            log.errorf("Provider did not connect within %d ms, giving up", initializationTimeoutMs);
            setError();
            throw new GeneralError("Provider failed to connect in time");
        }

        int state = this.state.get();
        if (state == SHUTDOWN) {
            throw new FatalError("Provider failed to connect");
        }
        if (state != READY) {
            throw new GeneralError("Provider failed to connect");
        }
    }

    public boolean wasEverReady() {
        int state = this.state.get();
        return state == READY || state == ERROR;
    }

    public boolean isShutdown() {
        return state.get() == SHUTDOWN;
    }

    public boolean isError() {
        int s = state.get();
        return s == ERROR || s == INITIAL_ERROR;
    }

    public void setReady() {
        state.getAndUpdate(s -> s == SHUTDOWN ? SHUTDOWN : READY);
        initializedBarrier.countDown();
    }

    public void setError() {
        state.getAndUpdate(s -> switch (s) {
            case SHUTDOWN -> SHUTDOWN;
            case CONNECTING, INITIAL_ERROR -> INITIAL_ERROR;
            default -> ERROR;
        });
        initializedBarrier.countDown();
    }

    public void setShutdown() {
        state.set(SHUTDOWN);
        initializedBarrier.countDown();
    }

    // only call from the Vert.x event loop

    public void scheduleReconnect(Runnable action) {
        if (isShutdown()) {
            return;
        }
        if (reconnectTimerId != -1) {
            return;
        }
        long delay = reconnectDelay;
        reconnectDelay = nextDelay(reconnectDelay);
        log.debugf("Scheduling reconnect in %d ms", delay);
        reconnectTimerId = vertx.setTimer(delay, id -> {
            reconnectTimerId = -1;
            action.run();
        });
    }

    public void resetReconnectDelay() {
        reconnectDelay = INITIAL_DELAY_MS;
    }

    public void cancelReconnect() {
        if (reconnectTimerId != -1) {
            vertx.cancelTimer(reconnectTimerId);
            reconnectTimerId = -1;
        }
    }

    // visible for tests
    long reconnectDelay() {
        return reconnectDelay;
    }
}
