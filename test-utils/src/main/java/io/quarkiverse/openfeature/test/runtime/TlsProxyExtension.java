package io.quarkiverse.openfeature.test.runtime;

import java.util.Objects;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;

/**
 * A JUnit 5 extension that manages a {@link GenericContainer} and a {@link TlsProxy} in front of it.
 * <p>
 * The extension starts the container and proxy in {@code beforeAll} and stops them in {@code afterAll}.
 * <p>
 * This extension must be registered <em>before</em> {@code QuarkusUnitTest} so that the container
 * and proxy are ready when the Quarkus app starts. Using the {@link org.junit.jupiter.api.Order @Order}
 * annotation is best.
 */
public class TlsProxyExtension implements BeforeAllCallback, AfterAllCallback {
    private final String keyPath;
    private final String certPath;
    private final GenericContainer<?> container;
    private final int backendPort;

    private TlsProxy proxy;

    private TlsProxyExtension(String keyPath, String certPath, GenericContainer<?> container, int backendPort) {
        this.keyPath = Objects.requireNonNull(keyPath);
        this.certPath = Objects.requireNonNull(certPath);
        this.container = Objects.requireNonNull(container);
        this.backendPort = backendPort;
    }

    /**
     * Creates an extension that starts the given container and proxies traffic to the specified port.
     * <p>
     * The port number is <em>inside the container</em>; this extension automatically translates it
     * to the port exposed by the container externally.
     */
    public static TlsProxyExtension create(String keyPath, String certPath, GenericContainer<?> container, int backendPort) {
        return new TlsProxyExtension(keyPath, certPath, container, backendPort);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        container.start();
        proxy = TlsProxy.start(keyPath, certPath, container.getHost(), container.getMappedPort(backendPort));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        proxy.stop();
        container.stop();
    }

    /**
     * Returns the port the proxy listens on. Only valid after {@code beforeAll} and before {@code afterAll}.
     */
    public int port() {
        return proxy.port();
    }
}
