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

    /**
     * The event type that tells the client that the flag configuration changed and has to be
     * fetched again. The relay proxy sends this since v1.56.0; before that, it sent a diff.
     */
    private static final String REFETCH_EVALUATION = "refetchEvaluation";

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
    private int currentConnectionId; // to drop re-fetches triggered by old connections
    private boolean refetchInFlight;
    private boolean anotherRefetchPending;

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

        currentConnectionId++;
        refetchInFlight = false;
        anotherRefetchPending = false;

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

                                // Buffer events received while fetching full config
                                boolean[] live = { false };
                                List<JsonNode> bufferedEvents = new ArrayList<>();
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
                                    JsonNode event;
                                    try {
                                        event = parseSseEvent(line.toString(StandardCharsets.UTF_8));
                                    } catch (Exception e) {
                                        // a diff is incremental, so dropping one would leave the data
                                        // permanently diverged; we have to reconnect and start from scratch
                                        sseResponse.handler(null);
                                        log.errorf(e, "Failed to parse SSE event data, will reconnect");
                                        fail.accept("Failed to parse flag event: " + e.getMessage());
                                        return;
                                    }
                                    if (event == null) {
                                        return;
                                    }
                                    if (live[0]) {
                                        handleSseEvent(event, port, listener, fail);
                                    } else {
                                        bufferedEvents.add(event);
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

                                fetchFullConfig(port, currentConnectionId, listener, fail, ignored -> {
                                    // the fetch may have read the configuration before a change that
                                    // a buffered event announces, so the diffs still have to be applied
                                    // and a re-fetch signal still has to lead to another fetch
                                    Set<String> changedKeys = new HashSet<>();
                                    boolean refetch = false;
                                    for (JsonNode event : bufferedEvents) {
                                        if (isRefetchSignal(event)) {
                                            refetch = true;
                                        } else {
                                            changedKeys.addAll(applyDiff(event));
                                        }
                                    }
                                    bufferedEvents.clear();
                                    live[0] = true;

                                    // Only a connection whose stream is still fine may restart
                                    // the backoff. The fetch succeeding says nothing about
                                    // the stream, and if the stream has failed, a reconnect
                                    // is pending and we must NOT reset the backoff.
                                    //
                                    // The data itself is still published though. It was fetched
                                    // in full and is current, so the provider goes ready and serves
                                    // it until the stream reconnect replaces it.
                                    if (!failed[0]) {
                                        state.resetReconnectDelay();
                                    }
                                    listener.onUpdate(state.isError());
                                    state.setReady();

                                    if (!changedKeys.isEmpty()) {
                                        log.debugf("Applied %d buffered flag diffs", changedKeys.size());
                                        listener.onConfigurationChanged(changedKeys);
                                    }
                                    if (refetch) {
                                        refetch(port, listener, fail);
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

    /**
     * Fetches the whole flag configuration over the connection identified by {@code connectionId}.
     * Everything that arrives once a new connection was re-established is dropped: applying a change
     * would overwrite the newer data that came with the new connection, and failing would push
     * a healthy connection into an unnecessary reconnect.
     */
    private void fetchFullConfig(int port, int connectionId, Listener listener, Consumer<String> fail,
            Consumer<Set<String>> onSuccess) {
        Consumer<String> failIfCurrent = msg -> {
            if (connectionId == currentConnectionId) {
                fail.accept(msg);
            }
        };

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
                                if (connectionId != currentConnectionId) {
                                    return;
                                }
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
                                    failIfCurrent.accept(msg);
                                    return;
                                }

                                body(response).onSuccess(body -> {
                                    if (connectionId != currentConnectionId) {
                                        return;
                                    }
                                    try {
                                        JsonNode root = mapper.readTree(body.toString());
                                        Set<String> changedKeys = applyFullConfig(root);
                                        log.debugf("Fetched %d flags from GO Feature Flag", flags.size());
                                        onSuccess.accept(changedKeys);
                                    } catch (Exception e) {
                                        log.errorf(e, "Failed to parse flag configuration");
                                        failIfCurrent.accept("Failed to parse flag configuration: " + e.getMessage());
                                    }
                                }).onFailure(t -> {
                                    log.warnf(t, "Failed to read response body");
                                    failIfCurrent.accept(t.getMessage());
                                });
                            }).onFailure(t -> {
                                log.warnf(t, "Failed to send request to GO Feature Flag");
                                failIfCurrent.accept(t.getMessage());
                            });
                })
                .onFailure(t -> {
                    log.warnf(t, "Failed to connect to GO Feature Flag at %s", config.url());
                    failIfCurrent.accept(t.getMessage());
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

    /**
     * Replaces all flag data with {@code root} and returns the keys whose definition changed.
     */
    private Set<String> applyFullConfig(JsonNode root) {
        Map<String, JsonNode> newFlags = new HashMap<>();
        JsonNode flagsNode = root.get("flags");
        if (flagsNode != null && flagsNode.isObject()) {
            flagsNode.forEachEntry(newFlags::put);
        }
        Map<String, JsonNode> oldFlags = this.flags;
        this.flags = newFlags;

        Set<String> changedKeys = new HashSet<>();
        newFlags.forEach((key, value) -> {
            if (!value.equals(oldFlags.get(key))) {
                changedKeys.add(key);
            }
        });
        oldFlags.forEach((key, value) -> {
            if (!newFlags.containsKey(key)) {
                changedKeys.add(key);
            }
        });

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

        return changedKeys;
    }

    /**
     * Returns the event carried by one SSE line, or {@code null} if the line carries none.
     * A line that does carry one but fails to parse is an error, and throws.
     */
    private JsonNode parseSseEvent(String line) throws JsonProcessingException {
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

    private void handleSseEvent(JsonNode event, int port, Listener listener, Consumer<String> fail) {
        if (isRefetchSignal(event)) {
            refetch(port, listener, fail);
            return;
        }

        Set<String> changedKeys = applyDiff(event);
        if (!changedKeys.isEmpty()) {
            log.debugf("Applied flag diff: %d keys changed", changedKeys.size());
            listener.onConfigurationChanged(changedKeys);
        }
    }

    private static boolean isRefetchSignal(JsonNode event) {
        JsonNode type = event.get("type");
        return type != null && REFETCH_EVALUATION.equals(type.asText());
    }

    /**
     * Fetches the whole configuration again in response to a re-fetch signal. Unlike a full fetch
     * after a connection is established, a refetch doesn't affect the reconnect backoff or readiness.
     */
    private void refetch(int port, Listener listener, Consumer<String> fail) {
        // overlapping fetches could apply their results out of order, so run one at a time
        // and remember that another signal arrived in the meantime
        if (refetchInFlight) {
            anotherRefetchPending = true;
            return;
        }
        refetchInFlight = true;

        fetchFullConfig(port, currentConnectionId, listener, fail, changedKeys -> {
            refetchInFlight = false;

            if (!changedKeys.isEmpty()) {
                log.debugf("Re-fetched flag configuration: %d keys changed", changedKeys.size());
                listener.onConfigurationChanged(changedKeys);
            }

            if (anotherRefetchPending) {
                anotherRefetchPending = false;
                refetch(port, listener, fail);
            }
        });
    }

    /**
     * Applies a flag diff pushed by the relay proxy. A flag diff is only pushed by relay proxies
     * before v1.56.0; newer ones only signal that the configuration changed and has to be re-fetched.
     * <p>
     * This method can be removed once those versions are out of support.
     */
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
