package io.quarkiverse.openfeature.runtime;

/**
 * Thrown when a provider is configured in a way it cannot work with at all, as opposed to merely
 * being unable to reach its flag server. A connection failure never fails application startup,
 * because a reconnect may still succeed; a misconfiguration does, because it's fatal.
 */
public class ProviderConfigurationException extends RuntimeException {
    public ProviderConfigurationException(String message) {
        super(message);
    }
}
