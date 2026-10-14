package io.quarkiverse.openfeature.test.runtime;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;

/**
 * gRPC server for timeout tests. It is a bare h2c server that knows nothing about gRPC
 * beyond the content type: the default handler accepts the call, answers the headers and
 * then never sends a single message, which is what a half-dead flagd looks like to a
 * sync client. The handler decides what, if anything, to answer.
 *
 * @see StallingHttpServer the equivalent for the HTTP providers
 */
public final class StallingGrpcServer {
    private final Vertx vertx;
    private final int port;

    private StallingGrpcServer(Vertx vertx, int port) {
        this.vertx = vertx;
        this.port = port;
    }

    /**
     * Starts a server that accepts calls, answers the response headers and then stays silent.
     */
    public static StallingGrpcServer start() {
        return start(request -> {
            // flush the response headers so that the call looks established, then send nothing
            request.response()
                    .putHeader("content-type", "application/grpc")
                    .write(Buffer.buffer());
        });
    }

    /**
     * Starts a server that passes each call to the given handler. The handler may answer
     * the call or leave it hanging.
     */
    public static StallingGrpcServer start(Handler<HttpServerRequest> handler) {
        Vertx vertx = Vertx.vertx();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<StallingGrpcServer> serverRef = new AtomicReference<>();

        HttpServerOptions options = new HttpServerOptions()
                .setAlpnVersions(List.of(HttpVersion.HTTP_2))
                .setHttp2ClearTextEnabled(true);

        vertx.createHttpServer(options)
                .requestHandler(handler)
                .listen(0)
                .onSuccess(s -> {
                    serverRef.set(new StallingGrpcServer(vertx, s.actualPort()));
                    latch.countDown();
                })
                .onFailure(t -> {
                    latch.countDown();
                });

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("StallingGrpcServer did not start in time");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        StallingGrpcServer server = serverRef.get();
        if (server == null) {
            throw new RuntimeException("StallingGrpcServer failed to start");
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
