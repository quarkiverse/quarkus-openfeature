package io.quarkiverse.openfeature.unleash.runtime;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import io.getunleash.engine.YggdrasilInvalidInputException;
import io.quarkiverse.openfeature.runtime.ProviderConfigurationException;
import io.quarkiverse.openfeature.runtime.SyncClientState;
import io.quarkiverse.openfeature.runtime.SyncClientUrls;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfigUtils;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

public class UnleashSyncClient {
    private static final Logger log = Logger.getLogger(UnleashSyncClient.class);

    private static final long NO_TIMER = -1;

    private final Vertx vertx;
    private final Context context;
    private final UnleashConfig.ProviderConfig config;
    private final TlsConfiguration tlsConfig; // null when TLS not configured
    private final String apiKey;
    private final SyncClientState state;

    // only accessed from the Vert.x event loop, no synchronization needed
    private long pollTimerId = NO_TIMER;
    private HttpClient httpClient;

    UnleashSyncClient(Vertx vertx, Context context, UnleashConfig.ProviderConfig config,
            TlsConfigurationRegistry tlsRegistry, String apiKey,
            SyncClientState state) {
        this.vertx = vertx;
        this.context = context;
        this.config = config;
        this.apiKey = apiKey;
        this.state = state;

        if (config.tlsConfigurationName().isPresent()) {
            String tlsName = config.tlsConfigurationName().get();
            this.tlsConfig = tlsRegistry.get(tlsName).orElseThrow(() -> new ProviderConfigurationException(
                    "TLS configuration '" + tlsName + "' not found, configure it with quarkus.tls.\"" + tlsName + "\".*"));
        } else {
            this.tlsConfig = null;
        }
    }

    void start(Listener listener) {
        context.runOnContext(v -> {
            // request timeout is cancelled once response headers arrive, so if the connection
            // delivers the headers but stops delivering the body in the middle, it wouldn't be
            // guarded by the request timeout and the response handler wouldn't be called either;
            // we have to enforce the timeout at the socket
            HttpClientOptions options = new HttpClientOptions()
                    .setReadIdleTimeout((int) config.requestTimeout().toMillis())
                    .setIdleTimeoutUnit(TimeUnit.MILLISECONDS);

            URI baseUri = URI.create(config.url());
            if (SyncClientUrls.isHttps(baseUri) || tlsConfig != null) {
                options.setSsl(true);

                if (tlsConfig != null) {
                    TlsConfigUtils.configure(options, tlsConfig);
                }
            }

            httpClient = vertx.createHttpClient(options);

            poll(listener);
        });
    }

    private void poll(Listener listener) {
        if (state.isShutdown()) {
            return;
        }

        URI baseUri = URI.create(config.url());
        int port = SyncClientUrls.port(baseUri);
        RequestOptions requestOptions = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(baseUri.getHost())
                .setPort(port)
                .setURI(SyncClientUrls.basePath(baseUri) + "/client/features")
                .setTimeout(config.requestTimeout().toMillis());

        httpClient.request(requestOptions)
                .onSuccess(request -> {
                    request.putHeader("Accept", "application/json");
                    if (apiKey != null) {
                        request.putHeader("Authorization", apiKey);
                    }

                    request.send()
                            .onSuccess(response -> {
                                if (response.statusCode() == 401 || response.statusCode() == 403) {
                                    String msg = "Unleash returned " + response.statusCode();
                                    log.errorf(msg);
                                    state.setShutdown();
                                    listener.onFatalError(msg);
                                    return;
                                }
                                if (response.statusCode() != 200) {
                                    String msg = "Unleash returned HTTP " + response.statusCode();
                                    log.warnf(msg);
                                    state.setError();
                                    listener.onError(msg);
                                    state.scheduleReconnect(() -> poll(listener));
                                    return;
                                }

                                response.body()
                                        .onSuccess(body -> {
                                            try {
                                                log.debugf("Polled Unleash state");
                                                state.resetReconnectDelay();
                                                listener.onUpdate(body.toString(), state.isError());
                                                state.setReady();
                                                scheduleNextPoll(listener);
                                            } catch (Exception e) {
                                                log.errorf(e, "Failed to parse Unleash state");
                                                state.setError();
                                                listener.onError("Failed to parse state: " + e.getMessage());
                                                state.scheduleReconnect(() -> poll(listener));
                                            }
                                        }).onFailure(t -> {
                                            log.warnf(t, "Failed to read Unleash response body");
                                            state.setError();
                                            listener.onError(t.getMessage());
                                            state.scheduleReconnect(() -> poll(listener));
                                        });
                            }).onFailure(t -> {
                                log.warnf(t, "Failed to send request to Unleash");
                                state.setError();
                                listener.onError(t.getMessage());
                                state.scheduleReconnect(() -> poll(listener));
                            });
                })
                .onFailure(t -> {
                    log.warnf(t, "Failed to connect to Unleash at %s", config.url());
                    state.setError();
                    listener.onError(t.getMessage());
                    state.scheduleReconnect(() -> poll(listener));
                });
    }

    private void scheduleNextPoll(Listener listener) {
        if (state.isShutdown()) {
            return;
        }
        pollTimerId = vertx.setTimer(config.pollInterval().toMillis(), id -> {
            pollTimerId = NO_TIMER;
            poll(listener);
        });
    }

    void awaitInitialized() throws Exception {
        state.awaitInitialized();
    }

    // called on the Vert.x event loop
    void shutdown() {
        if (pollTimerId != NO_TIMER) {
            vertx.cancelTimer(pollTimerId);
            pollTimerId = NO_TIMER;
        }
        state.cancelReconnect();
        if (httpClient != null) {
            httpClient.close();
        }
    }

    interface Listener {
        void onUpdate(String features, boolean reconnected) throws YggdrasilInvalidInputException;

        void onError(String message);

        void onFatalError(String message);
    }
}
