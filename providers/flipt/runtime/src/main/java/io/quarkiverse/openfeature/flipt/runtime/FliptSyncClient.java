package io.quarkiverse.openfeature.flipt.runtime;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.openfeature.runtime.SyncClientState;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfigUtils;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.parsetools.RecordParser;

public class FliptSyncClient {
    private static final Logger log = Logger.getLogger(FliptSyncClient.class);

    /**
     * A whole flag snapshot arrives as a single NDJSON line, so the limit has to be
     * generous; it only exists so that a server which never sends a newline cannot
     * make the client buffer without bound.
     */
    private static final int MAX_RECORD_SIZE = 32 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final Vertx vertx;
    private final Context context;
    private final FliptConfig.ProviderConfig config;
    private final FliptWasmEnginePool enginePool;
    private final TlsConfigurationRegistry tlsRegistry;
    private final String authHeader;
    private final SyncClientState state;

    // only accessed from the Vert.x event loop, no synchronization needed
    private HttpClient httpClient;

    FliptSyncClient(ObjectMapper mapper, Vertx vertx, Context context, FliptConfig.ProviderConfig config,
            FliptWasmEnginePool enginePool, TlsConfigurationRegistry tlsRegistry,
            String authHeader, SyncClientState state) {
        this.mapper = mapper;
        this.vertx = vertx;
        this.context = context;
        this.config = config;
        this.enginePool = enginePool;
        this.tlsRegistry = tlsRegistry;
        this.authHeader = authHeader;
        this.state = state;
    }

    void start(Listener listener) {
        context.runOnContext(v -> {
            httpClient = createHttpClient();
            if (authHeader != null) {
                verifyAuth(listener);
            } else {
                connectStream(listener);
            }
        });
    }

    private void verifyAuth(Listener listener) {
        URI baseUri = URI.create(config.url());
        int port = baseUri.getPort() > 0 ? baseUri.getPort() : (isHttps(baseUri) ? 443 : 8080);

        RequestOptions requestOptions = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(baseUri.getHost())
                .setPort(port)
                .setURI("/api/v1/auth/self");

        httpClient.request(requestOptions)
                .onSuccess(request -> {
                    request.putHeader("Authorization", authHeader);
                    request.send().onSuccess(response -> {
                        if (response.statusCode() == 401 || response.statusCode() == 403) {
                            String msg = "Flipt returned " + response.statusCode();
                            log.errorf(msg);
                            state.setShutdown();
                            listener.onFatalError(msg);
                        } else {
                            connectStream(listener);
                        }
                    }).onFailure(t -> {
                        log.debugf(t, "Auth verification request failed, proceeding to stream");
                        connectStream(listener);
                    });
                })
                .onFailure(t -> {
                    log.debugf(t, "Auth verification connection failed, proceeding to stream");
                    connectStream(listener);
                });
    }

    private void connectStream(Listener listener) {
        if (state.isShutdown()) {
            return;
        }

        URI baseUri = URI.create(config.url());
        String streamPath = String.format("/client/v2/environments/%s/namespaces/%s/stream",
                config.environment(), config.namespace());

        StringBuilder queryString = new StringBuilder();
        config.reference().ifPresent(ref -> queryString.append("reference=")
                .append(URLEncoder.encode(ref, StandardCharsets.UTF_8)));

        RequestOptions requestOptions = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(baseUri.getHost())
                .setPort(baseUri.getPort() > 0 ? baseUri.getPort() : (isHttps(baseUri) ? 443 : 8080))
                .setURI(queryString.length() > 0 ? streamPath + "?" + queryString : streamPath);

        httpClient.request(requestOptions)
                .onSuccess(request -> {
                    if (authHeader != null) {
                        request.putHeader("Authorization", authHeader);
                    }
                    request.putHeader("Accept", "application/x-ndjson");

                    request.send().onSuccess(response -> {
                        if (response.statusCode() == 401 || response.statusCode() == 403) {
                            String msg = "Flipt returned " + response.statusCode();
                            log.errorf(msg);
                            state.setShutdown();
                            listener.onFatalError(msg);
                            return;
                        }
                        if (response.statusCode() != 200) {
                            String msg = "Flipt returned HTTP " + response.statusCode();
                            log.warnf(msg);
                            state.setError();
                            listener.onError(msg);
                            state.scheduleReconnect(() -> connectStream(listener));
                            return;
                        }

                        boolean[] failed = { false };
                        Consumer<String> fail = msg -> {
                            if (failed[0] || state.isShutdown()) {
                                return;
                            }
                            failed[0] = true;
                            state.setError();
                            listener.onError(msg);
                            state.scheduleReconnect(() -> connectStream(listener));
                        };

                        RecordParser parser = RecordParser.newDelimited("\n",
                                line -> processLine(line.toString(StandardCharsets.UTF_8), listener));
                        parser.maxRecordSize(MAX_RECORD_SIZE);
                        parser.exceptionHandler(t -> {
                            // the oversized record stays buffered, so every further chunk would
                            // raise again; stop reading and let the reconnect start from scratch
                            response.handler(null);
                            request.connection().close();
                            log.warnf(t, "Flipt sync stream exceeded %d bytes per record, will reconnect",
                                    MAX_RECORD_SIZE);
                            fail.accept(t.getMessage());
                        });

                        response.handler(parser);
                        response.endHandler(v -> {
                            log.debug("Flipt sync stream completed, will reconnect");
                            fail.accept("stream completed unexpectedly");
                        });
                        response.exceptionHandler(t -> {
                            log.warnf(t, "Flipt sync stream error, will reconnect");
                            fail.accept(t.getMessage());
                        });
                    }).onFailure(t -> {
                        log.warnf(t, "Failed to send request to Flipt, will reconnect");
                        state.setError();
                        listener.onError(t.getMessage());
                        state.scheduleReconnect(() -> connectStream(listener));
                    });
                })
                .onFailure(t -> {
                    log.warnf(t, "Failed to connect to Flipt at %s, will reconnect", config.url());
                    state.setError();
                    listener.onError(t.getMessage());
                    state.scheduleReconnect(() -> connectStream(listener));
                });
    }

    private void processLine(String line, Listener listener) {
        line = line.trim();
        if (line.isEmpty()) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(line);
            JsonNode result = node.get("result");
            if (result == null) {
                log.debugf("Received NDJSON line without 'result' field: %s", line);
                return;
            }
            String snapshotJson = mapper.writeValueAsString(result);
            log.debugf("Received flag data from Flipt: %d bytes", snapshotJson.length());
            enginePool.updateSnapshot(snapshotJson);
            state.resetReconnectDelay();
            listener.onUpdate(state.isError());
            state.setReady();
        } catch (Exception e) {
            log.errorf(e, "Failed to process Flipt sync data");
            listener.onError("Failed to process flag data: " + e.getMessage());
        }
    }

    private HttpClient createHttpClient() {
        HttpClientOptions options = new HttpClientOptions();

        URI baseUri = URI.create(config.url());
        if (isHttps(baseUri) || config.tlsConfigurationName().isPresent()) {
            options.setSsl(true);

            if (config.tlsConfigurationName().isPresent()) {
                String tlsName = config.tlsConfigurationName().get();
                Optional<TlsConfiguration> tlsConfig = tlsRegistry.get(tlsName);
                if (tlsConfig.isEmpty()) {
                    throw new IllegalStateException("TLS configuration not found: " + tlsName);
                }
                TlsConfigUtils.configure(options, tlsConfig.get());
            }
        }

        return vertx.createHttpClient(options);
    }

    boolean isShutdown() {
        return state.isShutdown();
    }

    boolean wasEverReady() {
        return state.wasEverReady();
    }

    void awaitInitialized() throws Exception {
        state.awaitInitialized();
    }

    // called on the Vert.x event loop
    void shutdown() {
        state.cancelReconnect();
        if (httpClient != null) {
            httpClient.close();
        }
    }

    private static boolean isHttps(URI uri) {
        return "https".equals(uri.getScheme());
    }

    interface Listener {
        void onUpdate(boolean reconnected);

        void onError(String message);

        void onFatalError(String message);
    }
}
