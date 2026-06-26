package io.quarkiverse.openfeature.test.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;

/**
 * TCP proxy with simple fault injection. It can disallow connecting to the target server
 * ({@link #block()}) and allow it again ({@link #unblock()}).
 */
public final class TcpProxy {
    private final Vertx vertx;
    private final int port;
    private final Set<NetSocket> activeSockets = ConcurrentHashMap.newKeySet();

    private volatile boolean blocked;

    private TcpProxy(Vertx vertx, int port) {
        this.vertx = vertx;
        this.port = port;
    }

    public static TcpProxy start(String backendHost, int backendPort) {
        Vertx vertx = Vertx.vertx();
        NetClient client = vertx.createNetClient();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TcpProxy> proxyRef = new AtomicReference<>();

        vertx.createNetServer()
                .connectHandler(frontSocket -> {
                    TcpProxy proxy = proxyRef.get();

                    if (proxy.blocked) {
                        frontSocket.close();
                        return;
                    }

                    NetSocket[] backSocketRef = { null };
                    proxy.activeSockets.add(frontSocket);
                    frontSocket.closeHandler(v -> {
                        proxy.activeSockets.remove(frontSocket);
                        if (backSocketRef[0] != null) {
                            backSocketRef[0].close();
                        }
                    });
                    frontSocket.pause();

                    client.connect(backendPort, backendHost)
                            .onSuccess(backSocket -> {
                                backSocketRef[0] = backSocket;
                                proxy.activeSockets.add(backSocket);
                                backSocket.closeHandler(v -> {
                                    proxy.activeSockets.remove(backSocket);
                                    frontSocket.close();
                                });
                                frontSocket.handler(backSocket::write);
                                backSocket.handler(frontSocket::write);
                                frontSocket.exceptionHandler(t -> {
                                    frontSocket.close();
                                    backSocket.close();
                                });
                                backSocket.exceptionHandler(t -> {
                                    backSocket.close();
                                    frontSocket.close();
                                });
                                frontSocket.resume();
                            })
                            .onFailure(t -> frontSocket.close());
                })
                .listen(0)
                .onSuccess(s -> {
                    proxyRef.set(new TcpProxy(vertx, s.actualPort()));
                    latch.countDown();
                })
                .onFailure(t -> {
                    latch.countDown();
                });

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("TcpProxy did not start in time");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        TcpProxy proxy = proxyRef.get();
        if (proxy == null) {
            throw new RuntimeException("TcpProxy failed to start");
        }
        return proxy;
    }

    /**
     * Blocks connection to the proxied server. When called, all existing connections are
     * terminated and no new connections are allowed.
     */
    public void block() {
        blocked = true;
        for (NetSocket socket : activeSockets) {
            socket.close();
        }
    }

    /**
     * Unblocks connection to the proxied server. When called, connections are allowed again.
     */
    public void unblock() {
        blocked = false;
    }

    public int port() {
        return port;
    }

    public void stop() {
        vertx.close();
    }
}
