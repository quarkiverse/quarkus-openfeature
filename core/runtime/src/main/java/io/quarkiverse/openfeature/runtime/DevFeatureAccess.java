package io.quarkiverse.openfeature.runtime;

import java.util.Collection;

import dev.openfeature.sdk.FlagValueType;

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
     * A flag's key and its type, if known.
     *
     * @param key the flag key, must not be {@code null}
     * @param type the flag type, or {@code null} if unknown
     */
    record FlagInfo(String key, FlagValueType type) {
    }
}
