package io.quarkiverse.openfeature.gofeatureflag.runtime;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.openfeature.runtime.ProviderConfigurationException;
import io.quarkiverse.openfeature.runtime.SyncClientState;
import io.quarkiverse.openfeature.runtime.SyncClientUrls;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfigUtils;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.parsetools.RecordParser;

public class GoFeatureFlagSyncClient {
    private static final Logger log = Logger.getLogger(GoFeatureFlagSyncClient.class);

    /**
     * How long to wait for the response to the configuration fetch, and for the response
     * headers of the SSE stream. Not configurable: unlike the stream deadline, this only
     * has to accommodate a server that is reachable but busy.
     */
    private static final long REQUEST_TIMEOUT_MILLIS = 10_000;

    private final ObjectMapper mapper;
    private final Vertx vertx;
    private final Context context;
    private final GoFeatureFlagConfig.ProviderConfig config;
    private final URI baseUri;
    private final TlsConfiguration tlsConfig; // null when TLS not configured
    private final String apiKey;
    private final int maxConfigurationSize;
    private final SyncClientState state;

    private volatile Map<String, JsonNode> flags = Map.of();
    private volatile Map<String, Object> evaluationContextEnrichment;

    // only accessed from the Vert.x event loop, no synchronization needed
    private HttpClient httpClient;

    GoFeatureFlagSyncClient(ObjectMapper mapper, Vertx vertx, Context context,
            GoFeatureFlagConfig.ProviderConfig config,
            TlsConfigurationRegistry tlsRegistry, String apiKey, SyncClientState state) {
        this.mapper = mapper;
        this.vertx = vertx;
        this.context = context;
        this.config = config;
        this.baseUri = SyncClientUrls.parse("GO Feature Flag", config.url());
        this.apiKey = apiKey;
        this.state = state;

        // both the response body and a parser record are buffered into a single Buffer,
        // which cannot grow past an int; reject an impossible value here rather than on
        // the event loop, where nothing would see it
        long maxConfigurationSize = config.maxConfigurationSize().asLongValue();
        if (maxConfigurationSize <= 0 || maxConfigurationSize > Integer.MAX_VALUE) {
            throw new ProviderConfigurationException(
                    "The GO Feature Flag max-configuration-size of " + maxConfigurationSize
                            + " bytes is out of range, it must be between 1 and " + Integer.MAX_VALUE + " bytes");
        }
        this.maxConfigurationSize = (int) maxConfigurationSize;

        if (config.tlsConfigurationName().isPresent()) {
            String tlsName = config.tlsConfigurationName().get();
            this.tlsConfig = tlsRegistry.get(tlsName).orElseThrow(() -> new ProviderConfigurationException(
                    "TLS configuration '" + tlsName + "' not found, configure it with quarkus.tls.\"" + tlsName + "\".*"));
        } else {
            this.tlsConfig = null;
        }
    }

    JsonNode getFlag(String key) {
        return flags.get(key);
    }

    Map<String, JsonNode> getAllFlags() {
        return flags;
    }

    Map<String, Object> getEvaluationContextEnrichment() {
        return evaluationContextEnrichment;
    }

    void start(Listener listener) {
        context.runOnContext(v -> {
            fetchConfigAndConnectStream(listener);
        });
    }

    private void fetchConfigAndConnectStream(Listener listener) {
        if (state.isShutdown()) {
            return;
        }

        if (httpClient != null) {
            httpClient.close();
        }
        httpClient = createHttpClient();

        int port = SyncClientUrls.port(baseUri);

        // Connect SSE first, then fetch full config, to avoid missing
        // changes that occur between fetch and subscribe
        String sseUri = sseUri(SyncClientUrls.basePath(baseUri), apiKey);

        RequestOptions sseOptions = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(baseUri.getHost())
                .setPort(port)
                .setURI(sseUri)
                // only guards the wait for the response headers; once they arrive,
                // the stream deadline configured on the HTTP client takes over
                .setTimeout(REQUEST_TIMEOUT_MILLIS);

        httpClient.request(sseOptions)
                .onSuccess(request -> {
                    request.putHeader("Accept", "text/event-stream");

                    request.send()
                            .onSuccess(sseResponse -> {
                                if (sseResponse.statusCode() == 401 || sseResponse.statusCode() == 403) {
                                    String msg = "GO Feature Flag SSE returned " + sseResponse.statusCode();
                                    log.errorf(msg);
                                    state.setShutdown();
                                    listener.onFatalError(msg);
                                    return;
                                }
                                if (sseResponse.statusCode() != 200) {
                                    String msg = "GO Feature Flag SSE returned " + sseResponse.statusCode();
                                    log.warnf(msg);
                                    state.setError();
                                    listener.onError(msg);
                                    state.scheduleReconnect(() -> fetchConfigAndConnectStream(listener));
                                    return;
                                }

                                // Buffer diffs received while fetching full config
                                boolean[] live = { false };
                                List<JsonNode> bufferedDiffs = new ArrayList<>();
                                boolean[] failed = { false };
                                Consumer<String> fail = msg -> {
                                    if (failed[0] || state.isShutdown()) {
                                        return;
                                    }
                                    failed[0] = true;
                                    state.setError();
                                    listener.onError(msg);
                                    state.scheduleReconnect(() -> fetchConfigAndConnectStream(listener));
                                };

                                RecordParser parser = RecordParser.newDelimited("\n", line -> {
                                    JsonNode diff;
                                    try {
                                        diff = parseSseDiff(line.toString(StandardCharsets.UTF_8));
                                    } catch (Exception e) {
                                        // a diff is incremental, so dropping one would leave the data
                                        // permanently diverged; we have to reconnect and start from scratch
                                        sseResponse.handler(null);
                                        log.errorf(e, "Failed to parse SSE event data, will reconnect");
                                        fail.accept("Failed to parse flag diff: " + e.getMessage());
                                        return;
                                    }
                                    if (diff == null) {
                                        return;
                                    }
                                    if (live[0]) {
                                        Set<String> changedKeys = applyDiff(diff);
                                        if (!changedKeys.isEmpty()) {
                                            log.debugf("Applied flag diff: %d keys changed", changedKeys.size());
                                            listener.onConfigurationChanged(changedKeys);
                                        }
                                    } else {
                                        bufferedDiffs.add(diff);
                                    }
                                });
                                parser.maxRecordSize(maxConfigurationSize);
                                parser.exceptionHandler(t -> {
                                    // the oversized record stays buffered, so every further chunk would
                                    // raise again; stop reading and let the reconnect start from scratch
                                    sseResponse.handler(null);
                                    request.connection().close();
                                    String msg = "flag diff exceeds the configured maximum of " + maxConfigurationSize
                                            + " bytes, raise quarkus.openfeature.gofeatureflag.max-configuration-size";
                                    log.warnf(t, "GO Feature Flag SSE stream error, will reconnect: %s", msg);
                                    fail.accept(msg);
                                });

                                sseResponse.handler(parser);
                                sseResponse.endHandler(v -> {
                                    log.debug("GO Feature Flag SSE stream completed, will reconnect");
                                    fail.accept("SSE stream completed unexpectedly");
                                });
                                sseResponse.exceptionHandler(t -> {
                                    log.warnf(t, "GO Feature Flag SSE stream error, will reconnect");
                                    fail.accept(t.getMessage());
                                });

                                fetchFullConfig(port, listener, () -> {
                                    Set<String> changedKeys = new HashSet<>();
                                    for (JsonNode diff : bufferedDiffs) {
                                        changedKeys.addAll(applyDiff(diff));
                                    }
                                    bufferedDiffs.clear();
                                    live[0] = true;

                                    state.resetReconnectDelay();
                                    listener.onUpdate(state.isError());
                                    state.setReady();

                                    if (!changedKeys.isEmpty()) {
                                        log.debugf("Applied %d buffered flag diffs", changedKeys.size());
                                        listener.onConfigurationChanged(changedKeys);
                                    }
                                });
                            }).onFailure(t -> {
                                log.warnf(t, "Failed to send SSE request to GO Feature Flag");
                                state.setError();
                                listener.onError(t.getMessage());
                                state.scheduleReconnect(() -> fetchConfigAndConnectStream(listener));
                            });
                })
                .onFailure(t -> {
                    log.warnf(t, "Failed to connect SSE to GO Feature Flag at %s", config.url());
                    state.setError();
                    listener.onError(t.getMessage());
                    state.scheduleReconnect(() -> fetchConfigAndConnectStream(listener));
                });
    }

    private HttpClient createHttpClient() {
        // request timeout is cancelled once response headers arrive, so if the connection
        // delivers the headers but stops delivering the body in the middle, it wouldn't be
        // guarded by the request timeout and the response handler wouldn't be called either;
        // we have to enforce the timeout at the socket
        HttpClientOptions options = new HttpClientOptions()
                .setReadIdleTimeout((int) config.streamDeadline().toMillis())
                .setIdleTimeoutUnit(TimeUnit.MILLISECONDS);

        if (SyncClientUrls.isHttps(baseUri) || tlsConfig != null) {
            options.setSsl(true);

            if (tlsConfig != null) {
                TlsConfigUtils.configure(options, tlsConfig);
            }
        }

        return vertx.createHttpClient(options);
    }

    private void fetchFullConfig(int port, Listener listener, Runnable onSuccess) {
        RequestOptions requestOptions = new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setHost(baseUri.getHost())
                .setPort(port)
                .setURI(SyncClientUrls.basePath(baseUri) + "/v1/flag/configuration")
                .setTimeout(REQUEST_TIMEOUT_MILLIS);

        httpClient.request(requestOptions)
                .onSuccess(request -> {
                    if (apiKey != null) {
                        request.putHeader("Authorization", "Bearer " + apiKey);
                    }
                    request.putHeader("Content-Type", "application/json");

                    request.send(Buffer.buffer("{}"))
                            .onSuccess(response -> {
                                if (response.statusCode() == 401 || response.statusCode() == 403) {
                                    String msg = "GO Feature Flag returned " + response.statusCode();
                                    log.errorf(msg);
                                    state.setShutdown();
                                    listener.onFatalError(msg);
                                    return;
                                }
                                if (response.statusCode() != 200) {
                                    String msg = "GO Feature Flag returned " + response.statusCode();
                                    log.warnf(msg);
                                    state.setError();
                                    listener.onError(msg);
                                    state.scheduleReconnect(() -> fetchConfigAndConnectStream(listener));
                                    return;
                                }

                                body(response).onSuccess(body -> {
                                    try {
                                        JsonNode root = mapper.readTree(body.toString());
                                        applyFullConfig(root);
                                        log.debugf("Fetched %d flags from GO Feature Flag", flags.size());
                                        onSuccess.run();
                                    } catch (Exception e) {
                                        log.errorf(e, "Failed to parse flag configuration");
                                        state.setError();
                                        listener.onError("Failed to parse flag configuration: " + e.getMessage());
                                        state.scheduleReconnect(() -> fetchConfigAndConnectStream(listener));
                                    }
                                }).onFailure(t -> {
                                    log.warnf(t, "Failed to read response body");
                                    state.setError();
                                    listener.onError(t.getMessage());
                                    state.scheduleReconnect(() -> fetchConfigAndConnectStream(listener));
                                });
                            }).onFailure(t -> {
                                log.warnf(t, "Failed to send request to GO Feature Flag");
                                state.setError();
                                listener.onError(t.getMessage());
                                state.scheduleReconnect(() -> fetchConfigAndConnectStream(listener));
                            });
                })
                .onFailure(t -> {
                    log.warnf(t, "Failed to connect to GO Feature Flag at %s", config.url());
                    state.setError();
                    listener.onError(t.getMessage());
                    state.scheduleReconnect(() -> fetchConfigAndConnectStream(listener));
                });
    }

    /**
     * Like {@code response.body()}, except that it stops reading once the body grows past
     * {@link #maxConfigurationSize} instead of buffering whatever the server chooses to send.
     */
    private Future<Buffer> body(HttpClientResponse response) {
        Promise<Buffer> promise = Promise.promise();
        Buffer buffer = Buffer.buffer();
        response.handler(chunk -> {
            if (buffer.length() + chunk.length() > maxConfigurationSize) {
                promise.tryFail("flag configuration exceeds the configured maximum of " + maxConfigurationSize
                        + " bytes, raise quarkus.openfeature.gofeatureflag.max-configuration-size");
                response.request().reset();
                return;
            }
            buffer.appendBuffer(chunk);
        });
        response.endHandler(v -> {
            promise.tryComplete(buffer);
        });
        response.exceptionHandler(t -> {
            promise.tryFail(t);
        });
        return promise.future();
    }

    private void applyFullConfig(JsonNode root) {
        Map<String, JsonNode> newFlags = new HashMap<>();
        JsonNode flagsNode = root.get("flags");
        if (flagsNode != null && flagsNode.isObject()) {
            flagsNode.forEachEntry(newFlags::put);
        }
        this.flags = newFlags;

        JsonNode enrichment = root.get("evaluationContextEnrichment");
        if (enrichment != null && enrichment.isObject()) {
            Map<String, Object> map = new HashMap<>();
            enrichment.forEachEntry((key, value) -> {
                map.put(key, nodeToObject(value));
            });
            this.evaluationContextEnrichment = map;
        } else {
            this.evaluationContextEnrichment = null;
        }
    }

    /**
     * Returns the diff carried by one SSE line, or {@code null} if the line carries none.
     * A line that does carry one but fails to parse is an error, and throws.
     */
    private JsonNode parseSseDiff(String line) throws JsonProcessingException {
        line = line.trim();
        if (!line.startsWith("data:")) {
            return null;
        }

        String jsonData = line.substring(5).trim();
        if (jsonData.isEmpty()) {
            return null;
        }
        return mapper.readTree(jsonData);
    }

    private Set<String> applyDiff(JsonNode diff) {
        Set<String> changedKeys = new HashSet<>();
        Map<String, JsonNode> newFlags = new HashMap<>(this.flags);

        JsonNode deleted = diff.get("deleted");
        if (deleted != null && deleted.isObject()) {
            deleted.forEachEntry((key, value) -> {
                newFlags.remove(key);
                changedKeys.add(key);
            });
        }

        JsonNode added = diff.get("added");
        if (added != null && added.isObject()) {
            added.forEachEntry((key, value) -> {
                newFlags.put(key, value);
                changedKeys.add(key);
            });
        }

        JsonNode updated = diff.get("updated");
        if (updated != null && updated.isObject()) {
            updated.forEachEntry((key, value) -> {
                JsonNode newValue = value.get("new_value");
                if (newValue != null) {
                    newFlags.put(key, newValue);
                    changedKeys.add(key);
                }
            });
        }

        this.flags = newFlags;
        return changedKeys;
    }

    boolean isShutdown() {
        return state.isShutdown();
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

    // The SSE endpoint of the relay proxy only authorizes through the `apiKey` query
    // parameter; unlike the rest of the API, it ignores the `Authorization` header.
    // The key therefore has to go into the URI, where it must be encoded: it is an
    // arbitrary string, and an unencoded `&` or `#` would truncate it.
    //
    // visible for testing
    static String sseUri(String basePath, String apiKey) {
        String uri = basePath + "/stream/v1/sse/flag/change";
        if (apiKey != null) {
            uri += "?apiKey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        }
        return uri;
    }

    private static Object nodeToObject(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isInt()) {
            return node.asInt();
        } else if (node.isLong()) {
            return node.asLong();
        } else if (node.isDouble() || node.isFloat()) {
            return node.asDouble();
        }
        return node.toString();
    }

    interface Listener {
        void onUpdate(boolean reconnected);

        void onConfigurationChanged(Set<String> changedKeys);

        void onError(String message);

        void onFatalError(String message);
    }
}
