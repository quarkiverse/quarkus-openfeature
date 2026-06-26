package io.quarkiverse.openfeature.gofeatureflag.runtime;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.openfeature.runtime.SyncClientState;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfigUtils;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

public class GoFeatureFlagSyncClient {
    private static final Logger log = Logger.getLogger(GoFeatureFlagSyncClient.class);

    private final ObjectMapper mapper;
    private final Vertx vertx;
    private final Context context;
    private final GoFeatureFlagConfig.ProviderConfig config;
    private final TlsConfigurationRegistry tlsRegistry;
    private final String apiKey;
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
        this.tlsRegistry = tlsRegistry;
        this.apiKey = apiKey;
        this.state = state;
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

        URI baseUri = URI.create(config.url());
        int port = baseUri.getPort() > 0 ? baseUri.getPort() : (isHttps(baseUri) ? 443 : 1031);

        // Connect SSE first, then fetch full config, to avoid missing
        // changes that occur between fetch and subscribe
        String sseUri = "/stream/v1/sse/flag/change";
        if (apiKey != null) {
            sseUri += "?apiKey=" + apiKey;
        }

        RequestOptions sseOptions = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(baseUri.getHost())
                .setPort(port)
                .setURI(sseUri);

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
                                String[] remainder = { "" };

                                sseResponse.handler(chunk -> {
                                    List<JsonNode> diffs = parseSseDiffs(remainder, chunk.toString());
                                    if (live[0]) {
                                        for (JsonNode diff : diffs) {
                                            Set<String> changedKeys = applyDiff(diff);
                                            if (!changedKeys.isEmpty()) {
                                                log.debugf("Applied flag diff: %d keys changed", changedKeys.size());
                                                listener.onConfigurationChanged(changedKeys);
                                            }
                                        }
                                    } else {
                                        bufferedDiffs.addAll(diffs);
                                    }
                                });
                                sseResponse.endHandler(v -> {
                                    log.debug("GO Feature Flag SSE stream completed, will reconnect");
                                    state.setError();
                                    listener.onError("SSE stream completed unexpectedly");
                                    state.scheduleReconnect(() -> fetchConfigAndConnectStream(listener));
                                });
                                sseResponse.exceptionHandler(t -> {
                                    if (state.isShutdown()) {
                                        return;
                                    }
                                    log.warnf(t, "GO Feature Flag SSE stream error, will reconnect");
                                    state.setError();
                                    listener.onError(t.getMessage());
                                    state.scheduleReconnect(() -> fetchConfigAndConnectStream(listener));
                                });

                                fetchFullConfig(baseUri, port, listener, () -> {
                                    Set<String> changedKeys = new HashSet<>();
                                    for (JsonNode diff : bufferedDiffs) {
                                        changedKeys.addAll(applyDiff(diff));
                                    }
                                    bufferedDiffs.clear();
                                    live[0] = true;

                                    state.resetReconnectDelay();
                                    boolean reconnected = state.setReadyAndWasError();
                                    listener.onUpdate(reconnected);

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

    private void fetchFullConfig(URI baseUri, int port, Listener listener, Runnable onSuccess) {
        RequestOptions requestOptions = new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setHost(baseUri.getHost())
                .setPort(port)
                .setURI("/v1/flag/configuration");

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

                                response.body().onSuccess(body -> {
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

    private List<JsonNode> parseSseDiffs(String[] remainder, String chunk) {
        String data = remainder[0] + chunk;
        int lastNewline = data.lastIndexOf('\n');
        if (lastNewline < 0) {
            remainder[0] = data;
            return List.of();
        }

        String complete = data.substring(0, lastNewline);
        remainder[0] = data.substring(lastNewline + 1);

        List<JsonNode> diffs = new ArrayList<>();
        for (String line : complete.split("\n")) {
            line = line.trim();
            if (line.startsWith("data:")) {
                String jsonData = line.substring(5).trim();
                if (jsonData.isEmpty()) {
                    continue;
                }
                try {
                    diffs.add(mapper.readTree(jsonData));
                } catch (Exception e) {
                    log.errorf(e, "Failed to parse SSE event data");
                }
            }
        }
        return diffs;
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

    private static boolean isHttps(URI uri) {
        return "https".equals(uri.getScheme());
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
