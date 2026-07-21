package io.quarkiverse.openfeature.runtime;

import java.util.Collection;
import java.util.List;

import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.ProviderEvent;

/**
 * Optional contract for feature providers that expose their known flags.
 * Used by dev UI to browse and evaluate feature flags.
 */
public interface DevFeatureAccess {
    /**
     * Returns all flags currently known to the provider.
     */
    Collection<FlagInfo> getFlags();

    /**
     * Returns provider events recorded since startup, if supported.
     */
    default List<EventInfo> getEventLog() {
        return List.of();
    }

    /**
     * A flag's key and its type, if known.
     *
     * @param key the flag key, must not be {@code null}
     * @param type the flag type, or {@code null} if unknown
     */
    record FlagInfo(String key, FlagValueType type) {
    }

    /**
     * A provider event with a timestamp, type, and message.
     *
     * @param timestamp the time the event occurred, in milliseconds since epoch
     * @param type the provider event type
     * @param message a human-readable message describing the event
     */
    record EventInfo(long timestamp, ProviderEvent type, String message) {
    }
}
