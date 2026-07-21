package io.quarkiverse.openfeature.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.jboss.logging.Logger;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderEventDetails;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

public abstract class AbstractRemoteFeatureProvider extends EventProvider implements DevFeatureAccess, OverrideFeatureAccess {
    private static final Logger log = Logger.getLogger(AbstractRemoteFeatureProvider.class);

    private static final int MAX_EVENTS = 100;

    private final Vertx vertx;
    private final Context context;
    private final Duration gracePeriod;
    private final SyncClientState syncState;

    private final Deque<DevFeatureAccess.EventInfo> eventLog = new ConcurrentLinkedDeque<>();

    private volatile FlagOverrides flagOverrides;

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
        recordEvent(ProviderEvent.PROVIDER_READY, "reconnected");
        emitProviderReady(ProviderEventDetails.builder()
                .message("reconnected")
                .build());
    }

    protected final void handleReconnected(List<String> flagsChanged) {
        cancelErrorTimer();
        recordEvent(ProviderEvent.PROVIDER_READY, "reconnected");
        emitProviderReady(ProviderEventDetails.builder()
                .flagsChanged(flagsChanged)
                .message("reconnected")
                .build());
    }

    protected final void handleConfigurationChanged(String message) {
        recordEvent(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, message);
        emitProviderConfigurationChanged(ProviderEventDetails.builder()
                .message(message)
                .build());
    }

    protected final void handleConfigurationChanged(String message, List<String> flagsChanged) {
        recordEvent(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, message);
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
            recordEvent(ProviderEvent.PROVIDER_ERROR, message);
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
        recordEvent(ProviderEvent.PROVIDER_STALE, message);
        emitProviderStale(ProviderEventDetails.builder()
                .message(message)
                .build());
        errorTimerId = vertx.setTimer(gracePeriod.toMillis(), id -> {
            if (!syncState.isShutdown()) {
                log.errorf("Provider did not reconnect within %d seconds, emitting ERROR",
                        gracePeriod.toSeconds());
                recordEvent(ProviderEvent.PROVIDER_ERROR, message);
                emitProviderError(ProviderEventDetails.builder()
                        .message(message)
                        .build());
            }
        });
    }

    protected final void handleFatalError(String message) {
        cancelErrorTimer();
        recordEvent(ProviderEvent.PROVIDER_ERROR, message);
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

    private void recordEvent(ProviderEvent type, String message) {
        eventLog.addLast(new DevFeatureAccess.EventInfo(System.currentTimeMillis(), type, message));
        while (eventLog.size() > MAX_EVENTS) {
            eventLog.pollFirst();
        }
    }

    @Override
    public List<DevFeatureAccess.EventInfo> getEventLog() {
        return new ArrayList<>(eventLog);
    }

    @Override
    public final void setFlagOverrides(FlagOverrides overrides) {
        this.flagOverrides = overrides;
    }

    @Override
    public final void clearFlagOverrides() {
        this.flagOverrides = null;
    }

    protected final <T> ProviderEvaluation<T> evaluateFlagOverride(String key, Class<T> expectedType) {
        FlagOverrides flagOverrides = this.flagOverrides;
        return flagOverrides != null ? flagOverrides.evaluate(key, expectedType) : null;
    }
}
