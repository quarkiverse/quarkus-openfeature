package io.quarkiverse.openfeature.runtime;

/**
 * Optional contract for feature providers that support test flag overrides.
 * <p>
 * When a provider implements this interface, the JUnit {@code @TestFlag}
 * annotation can temporarily override flag values for the duration
 * of a test. Non-overridden flags fall through to the provider's
 * normal evaluation.
 */
public interface TestFeatureAccess {
    /**
     * Sets flag overrides for the current test. Any flag evaluation for a key present in the given
     * {@code overrides} returns the override value instead of the provider's real value. Values
     * must be pre-parsed to the expected type.
     */
    void setTestOverrides(TestOverrides overrides);

    /**
     * Clears all test overrides, restoring normal provider evaluation.
     */
    void clearTestOverrides();
}
