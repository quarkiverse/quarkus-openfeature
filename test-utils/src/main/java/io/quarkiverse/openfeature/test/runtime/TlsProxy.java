package io.quarkiverse.openfeature.test.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;

/**
 * TLS proxy for testing applications that do not support TLS termination themselves.
 * These applications typically recommend terminating TLS on a load balancer, which
 * this proxy simulates.
 */
public final class TlsProxy {
    private final Vertx vertx;
    private final int port;

    private TlsProxy(Vertx vertx, int port) {
        this.vertx = vertx;
        this.port = port;
    }

    public static TlsProxy start(String keyPath, String certPath, String backendHost, int backendPort) {
        Vertx vertx = Vertx.vertx();
        HttpClient backendClient = vertx.createHttpClient();

        HttpServerOptions options = new HttpServerOptions()
                .setSsl(true)
                .setKeyCertOptions(new PemKeyCertOptions()
                        .setKeyPath(keyPath)
                        .setCertPath(certPath));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TlsProxy> proxyRef = new AtomicReference<>();

        vertx.createHttpServer(options)
                .requestHandler(frontReq -> {
                    frontReq.pause();
                    backendClient.request(frontReq.method(), backendPort, backendHost, frontReq.uri())
                            .onSuccess(backReq -> {
                                frontReq.headers().forEach(h -> backReq.putHeader(h.getKey(), h.getValue()));
                                backReq.putHeader("Host", backendHost + ":" + backendPort);

                                frontReq.resume();
                                frontReq.body()
                                        .onSuccess(body -> {
                                            Buffer sendBody = body.length() > 0 ? body : null;
                                            (sendBody != null ? backReq.send(sendBody) : backReq.send())
                                                    .onSuccess(backResp -> {
                                                        frontReq.response().setStatusCode(backResp.statusCode());
                                                        backResp.headers().forEach(h -> {
                                                            String name = h.getKey().toLowerCase();
                                                            if (!name.equals("content-length")
                                                                    && !name.equals("transfer-encoding")) {
                                                                frontReq.response().putHeader(h.getKey(), h.getValue());
                                                            }
                                                        });
                                                        frontReq.response().setChunked(true);
                                                        String contentType = backResp.getHeader("Content-Type");
                                                        if (contentType != null
                                                                && contentType.startsWith("text/event-stream")) {
                                                            // force headers to be sent for SSE
                                                            frontReq.response().write(Buffer.buffer(": proxy\n"));
                                                        }
                                                        backResp.handler(frontReq.response()::write);
                                                        backResp.endHandler(v -> frontReq.response().end());
                                                    })
                                                    .onFailure(t -> frontReq.response().setStatusCode(502).end());
                                        })
                                        .onFailure(t -> frontReq.response().setStatusCode(502).end());
                            })
                            .onFailure(t -> frontReq.response().setStatusCode(502).end());
                })
                .listen(0)
                .onSuccess(s -> {
                    proxyRef.set(new TlsProxy(vertx, s.actualPort()));
                    latch.countDown();
                })
                .onFailure(t -> {
                    latch.countDown();
                });

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("TlsProxy did not start in time");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        TlsProxy proxy = proxyRef.get();
        if (proxy == null) {
            throw new RuntimeException("TlsProxy failed to start");
        }
        return proxy;
    }

    public int port() {
        return port;
    }

    public void stop() {
        vertx.close();
    }
}
