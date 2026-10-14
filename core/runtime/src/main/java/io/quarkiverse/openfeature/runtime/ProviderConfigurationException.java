package io.quarkiverse.openfeature.runtime;

/**
 * Thrown when a provider is configured in a way it cannot work with at all, as opposed to merely
 * being unable to reach its flag server. A connection failure never fails application startup,
 * because a reconnect may still succeed; a misconfiguration does, because it's fatal.
 * <p>
 * Must be thrown while the provider is created, not while it is initialized. Initialization
 * is asynchronous (unless {@code quarkus.openfeature.await-providers} is turned on, which it
 * typically is not in production), and an exception thrown there would only be logged.
 */
public class ProviderConfigurationException extends RuntimeException {
    public ProviderConfigurationException(String message) {
        super(message);
    }
}
