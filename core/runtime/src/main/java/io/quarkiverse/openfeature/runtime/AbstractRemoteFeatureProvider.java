package io.quarkiverse.openfeature.runtime;

import java.time.Duration;
import java.util.List;

import org.jboss.logging.Logger;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

public abstract class AbstractRemoteFeatureProvider extends EventProvider implements DevFeatureAccess, TestFeatureAccess {
    private static final Logger log = Logger.getLogger(AbstractRemoteFeatureProvider.class);

    private final Vertx vertx;
    private final Context context;
    private final Duration gracePeriod;
    private final SyncClientState syncState;

    private volatile TestOverrides testOverrides;

    // only accessed from the Vert.x event loop, no synchronization needed
    private Long errorTimerId;

    protected AbstractRemoteFeatureProvider(Vertx vertx, Duration gracePeriod, SyncClientState syncState) {
        this.vertx = vertx;
        this.context = vertx.getOrCreateContext();
        this.gracePeriod = gracePeriod;
        this.syncState = syncState;
    }

    protected final Context context() {
        return context;
    }

    protected final void cancelErrorTimer() {
        Long timerId = errorTimerId;
        if (timerId != null) {
            vertx.cancelTimer(timerId);
            errorTimerId = null;
        }
    }

    protected final void handleReconnected() {
        cancelErrorTimer();
        emitProviderReady(ProviderEventDetails.builder()
                .message("reconnected")
                .build());
    }

    protected final void handleReconnected(List<String> flagsChanged) {
        cancelErrorTimer();
        emitProviderReady(ProviderEventDetails.builder()
                .flagsChanged(flagsChanged)
                .message("reconnected")
                .build());
    }

    protected final void handleConfigurationChanged(String message) {
        emitProviderConfigurationChanged(ProviderEventDetails.builder()
                .message(message)
                .build());
    }

    protected final void handleConfigurationChanged(String message, List<String> flagsChanged) {
        emitProviderConfigurationChanged(ProviderEventDetails.builder()
                .flagsChanged(flagsChanged)
                .message(message)
                .build());
    }

    protected final void handleError(String message) {
        if (syncState.isShutdown()) {
            return;
        }
        if (!syncState.wasEverReady()) {
            emitProviderError(ProviderEventDetails.builder()
                    .message(message)
                    .build());
            return;
        }
        if (errorTimerId != null) {
            return;
        }
        log.debugf("Stream error, emitting STALE and scheduling ERROR in %d seconds",
                gracePeriod.toSeconds());
        emitProviderStale(ProviderEventDetails.builder()
                .message(message)
                .build());
        errorTimerId = vertx.setTimer(gracePeriod.toMillis(), id -> {
            if (!syncState.isShutdown()) {
                log.errorf("Provider did not reconnect within %d seconds, emitting ERROR",
                        gracePeriod.toSeconds());
                emitProviderError(ProviderEventDetails.builder()
                        .message(message)
                        .build());
            }
        });
    }

    protected final void handleFatalError(String message) {
        cancelErrorTimer();
        emitProviderError(ProviderEventDetails.builder()
                .errorCode(ErrorCode.PROVIDER_FATAL)
                .message(message)
                .build());
    }

    @Override
    public final void shutdown() {
        syncState.setShutdown();
        context.runOnContext(v -> {
            cancelErrorTimer();
            doShutdown();
        });
        super.shutdown();
    }

    /**
     * Called on the Vert.x event loop during provider shutdown.
     * Event-loop-only fields (timers, clients) can be accessed directly.
     */
    protected abstract void doShutdown();

    @Override
    public final void setTestOverrides(TestOverrides overrides) {
        this.testOverrides = overrides;
    }

    @Override
    public final void clearTestOverrides() {
        this.testOverrides = null;
    }

    protected final <T> ProviderEvaluation<T> evaluateTestOverride(String key, Class<T> expectedType) {
        TestOverrides testOverrides = this.testOverrides;
        return testOverrides != null ? testOverrides.evaluate(key, expectedType) : null;
    }
}
