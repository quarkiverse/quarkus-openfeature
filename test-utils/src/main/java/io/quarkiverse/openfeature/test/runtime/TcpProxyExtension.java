package io.quarkiverse.openfeature.test.runtime;

import java.util.Objects;
import java.util.function.Consumer;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;

/**
 * A JUnit 5 extension that manages a {@link GenericContainer} and a {@link TcpProxy} in front of it,
 * providing network fault injection for reconnection tests.
 * <p>
 * The extension starts the container and proxy in {@code beforeAll} and stops them in {@code afterAll}.
 * In {@code beforeEach}, it checks the test method for {@link Disconnect} and {@link Reconnect} annotations
 * and acts on the proxy accordingly.
 * <p>
 * This extension must be registered <em>before</em> {@code QuarkusUnitTest} so that the container
 * and proxy are ready when the Quarkus app starts. Using the {@link org.junit.jupiter.api.Order @Order}
 * annotation is best.
 */
public class TcpProxyExtension implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback {
    private final GenericContainer<?> container;
    private final Consumer<GenericContainer<?>> afterStart;
    private final int backendPort;

    private TcpProxy proxy;

    private TcpProxyExtension(GenericContainer<?> container, Consumer<GenericContainer<?>> afterStart, int backendPort) {
        this.container = Objects.requireNonNull(container);
        this.afterStart = afterStart;
        this.backendPort = backendPort;
    }

    /**
     * Creates an extension that starts the given container and proxies traffic to the specified port.
     * <p>
     * The port number is <em>inside the container</em>; this extension automatically translates it
     * to the port exposed by the container externally.
     */
    public static TcpProxyExtension create(GenericContainer<?> container, int backendPort) {
        return new TcpProxyExtension(container, null, backendPort);
    }

    /**
     * Creates an extension that starts the given container, runs a post-start action (e.g. importing
     * test data), and proxies traffic to the specified port.
     * <p>
     * The port number is <em>inside the container</em>; this extension automatically translates it
     * to the port exposed by the container externally.
     */
    public static TcpProxyExtension create(GenericContainer<?> container, int backendPort,
            Consumer<GenericContainer<?>> afterStart) {
        return new TcpProxyExtension(container, afterStart, backendPort);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        container.start();
        if (afterStart != null) {
            afterStart.accept(container);
        }
        proxy = TcpProxy.start(container.getHost(), container.getMappedPort(backendPort));
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        if (context.getRequiredTestMethod().isAnnotationPresent(Disconnect.class)) {
            proxy.block();
        } else if (context.getRequiredTestMethod().isAnnotationPresent(Reconnect.class)) {
            proxy.unblock();
        }
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
