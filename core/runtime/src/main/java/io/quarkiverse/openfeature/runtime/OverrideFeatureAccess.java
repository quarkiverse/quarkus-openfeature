package io.quarkiverse.openfeature.runtime;

/**
 * Optional contract for feature providers that support flag overrides.
 * <p>
 * When a provider implements this interface, flag values can be
 * temporarily overridden (e.g. via {@code @TestFlag} in tests
 * or the Dev UI in dev mode). Non-overridden flags fall through
 * to the provider's normal evaluation.
 */
public interface OverrideFeatureAccess {
    /**
     * Sets flag overrides. Any flag evaluation for a key present in the given
     * {@code overrides} returns the override value instead of the provider's real value. Values
     * must be pre-parsed to the expected type.
     */
    void setFlagOverrides(FlagOverrides overrides);

    /**
     * Clears all flag overrides, restoring normal provider evaluation.
     */
    void clearFlagOverrides();
}
