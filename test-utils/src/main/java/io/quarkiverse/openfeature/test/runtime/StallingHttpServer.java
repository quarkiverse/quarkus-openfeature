package io.quarkiverse.openfeature.test.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;

/**
 * HTTP server for timeout tests. The handler decides what, if anything, to answer;
 * the default handler never answers at all, which is what a half-open connection
 * looks like to a client.
 */
public final class StallingHttpServer {
    private final Vertx vertx;
    private final int port;

    private StallingHttpServer(Vertx vertx, int port) {
        this.vertx = vertx;
        this.port = port;
    }

    /**
     * Starts a server that accepts requests and never responds to any of them.
     */
    public static StallingHttpServer start() {
        return start(request -> {
        });
    }

    /**
     * Starts a server that passes each request to the given handler. The handler may answer
     * the request or leave it hanging.
     */
    public static StallingHttpServer start(Handler<HttpServerRequest> handler) {
        Vertx vertx = Vertx.vertx();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<StallingHttpServer> serverRef = new AtomicReference<>();

        vertx.createHttpServer()
                .requestHandler(handler)
                .listen(0)
                .onSuccess(s -> {
                    serverRef.set(new StallingHttpServer(vertx, s.actualPort()));
                    latch.countDown();
                })
                .onFailure(t -> {
                    latch.countDown();
                });

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("StallingHttpServer did not start in time");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        StallingHttpServer server = serverRef.get();
        if (server == null) {
            throw new RuntimeException("StallingHttpServer failed to start");
        }
        return server;
    }

    public int port() {
        return port;
    }

    public void stop() {
        vertx.close();
    }
}
