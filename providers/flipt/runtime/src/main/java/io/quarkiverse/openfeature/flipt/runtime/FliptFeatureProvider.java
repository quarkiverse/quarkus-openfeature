package io.quarkiverse.openfeature.flipt.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import io.quarkiverse.openfeature.runtime.AbstractRemoteFeatureProvider;
import io.quarkiverse.openfeature.runtime.DevFeatureAccess.FlagInfo;
import io.quarkiverse.openfeature.runtime.SyncClientState;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

// Concurrency: flag data is updated from the Vert.x event loop and evaluated
// from request threads. This is safe because each evaluation acquires a WASM
// engine instance from a pool, and each instance is used by one thread at a time.
public class FliptFeatureProvider extends AbstractRemoteFeatureProvider {
    private static final Logger log = Logger.getLogger(FliptFeatureProvider.class);

    private final ObjectMapper mapper;
    private final FliptWasmEnginePool enginePool;
    private final FliptSyncClient syncClient;

    FliptFeatureProvider(ObjectMapper mapper, FliptWasmEnginePool enginePool, Vertx vertx,
            FliptConfig.ProviderConfig config, TlsConfigurationRegistry tlsRegistry,
            String authHeader) {
        this(mapper, enginePool, vertx, config, tlsRegistry, authHeader, new SyncClientState(vertx));
    }

    private FliptFeatureProvider(ObjectMapper mapper, FliptWasmEnginePool enginePool,
            Vertx vertx, FliptConfig.ProviderConfig config, TlsConfigurationRegistry tlsRegistry,
            String authHeader, SyncClientState syncState) {
        super(vertx, config.gracePeriod(), syncState);
        this.mapper = mapper;
        this.enginePool = enginePool;
        this.syncClient = new FliptSyncClient(mapper, vertx, context(), config, enginePool, tlsRegistry, authHeader, syncState);
    }

    @Override
    public Metadata getMetadata() {
        return () -> "flipt";
    }

    @Override
    public void initialize(EvaluationContext evaluationContext) throws Exception {
        syncClient.start(new FliptSyncClient.Listener() {
            @Override
            public void onUpdate(boolean reconnected) {
                if (syncClient.isShutdown()) {
                    return;
                }
                if (reconnected) {
                    handleReconnected();
                } else {
                    handleConfigurationChanged("flags updated");
                }
            }

            @Override
            public void onError(String message) {
                handleError(message);
            }

            @Override
            public void onFatalError(String message) {
                handleFatalError(message);
            }
        });
        syncClient.awaitInitialized();
    }

    @Override
    protected void doShutdown() {
        syncClient.shutdown();
        enginePool.close();
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Boolean> override = evaluateTestOverride(key, Boolean.class);
        if (override != null) {
            return override;
        }
        String requestJson = buildEvaluationRequest(key, ctx);
        String responseJson = enginePool.evaluateBoolean(requestJson);
        try {
            JsonNode response = parseWasmResponse(responseJson);
            return ProviderEvaluation.<Boolean> builder()
                    .value(response.path("enabled").asBoolean())
                    .reason(mapReason(response.path("reason").asText()))
                    .build();
        } catch (Exception e) {
            return ProviderEvaluation.<Boolean> builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.name())
                    .errorCode(ErrorCode.GENERAL)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<String> override = evaluateTestOverride(key, String.class);
        if (override != null) {
            return override;
        }
        String requestJson = buildEvaluationRequest(key, ctx);
        String responseJson = enginePool.evaluateVariant(requestJson);
        try {
            JsonNode response = parseWasmResponse(responseJson);
            String variantKey = response.path("variant_key").asText();
            return ProviderEvaluation.<String> builder()
                    .value(variantKey)
                    .variant(variantKey)
                    .reason(mapReason(response.path("reason").asText()))
                    .build();
        } catch (Exception e) {
            return ProviderEvaluation.<String> builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.name())
                    .errorCode(ErrorCode.GENERAL)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Integer> override = evaluateTestOverride(key, Integer.class);
        if (override != null) {
            return override;
        }
        String requestJson = buildEvaluationRequest(key, ctx);
        String responseJson = enginePool.evaluateVariant(requestJson);
        try {
            JsonNode response = parseWasmResponse(responseJson);
            String variantKey = response.path("variant_key").asText();
            return ProviderEvaluation.<Integer> builder()
                    .value(Integer.parseInt(variantKey))
                    .variant(variantKey)
                    .reason(mapReason(response.path("reason").asText()))
                    .build();
        } catch (NumberFormatException e) {
            return ProviderEvaluation.<Integer> builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.name())
                    .errorCode(ErrorCode.TYPE_MISMATCH)
                    .errorMessage("Variant key is not an integer: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            return ProviderEvaluation.<Integer> builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.name())
                    .errorCode(ErrorCode.GENERAL)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Double> override = evaluateTestOverride(key, Double.class);
        if (override != null) {
            return override;
        }
        String requestJson = buildEvaluationRequest(key, ctx);
        String responseJson = enginePool.evaluateVariant(requestJson);
        try {
            JsonNode response = parseWasmResponse(responseJson);
            String variantKey = response.path("variant_key").asText();
            return ProviderEvaluation.<Double> builder()
                    .value(Double.parseDouble(variantKey))
                    .variant(variantKey)
                    .reason(mapReason(response.path("reason").asText()))
                    .build();
        } catch (NumberFormatException e) {
            return ProviderEvaluation.<Double> builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.name())
                    .errorCode(ErrorCode.TYPE_MISMATCH)
                    .errorMessage("Variant key is not a number: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            return ProviderEvaluation.<Double> builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.name())
                    .errorCode(ErrorCode.GENERAL)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Value> override = evaluateTestOverride(key, Value.class);
        if (override != null) {
            return override;
        }
        String requestJson = buildEvaluationRequest(key, ctx);
        String responseJson = enginePool.evaluateVariant(requestJson);
        try {
            JsonNode response = parseWasmResponse(responseJson);
            String variantKey = response.path("variant_key").asText();
            String variantAttachment = response.path("variant_attachment").asText(null);
            Value value = variantAttachment != null ? parseJsonValue(variantAttachment) : new Value(variantKey);
            return ProviderEvaluation.<Value> builder()
                    .value(value)
                    .variant(variantKey)
                    .reason(mapReason(response.path("reason").asText()))
                    .build();
        } catch (Exception e) {
            return ProviderEvaluation.<Value> builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.name())
                    .errorCode(ErrorCode.GENERAL)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private String buildEvaluationRequest(String flagKey, EvaluationContext ctx) {
        try {
            ObjectNode result = mapper.createObjectNode();
            result.put("flag_key", flagKey);
            result.put("entity_id", ctx != null && ctx.getTargetingKey() != null ? ctx.getTargetingKey() : "");

            if (ctx != null && !ctx.asMap().isEmpty()) {
                result.set("context", mapper.valueToTree(ctx.asObjectMap()));
            }
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build evaluation request", e);
        }
    }

    private JsonNode parseWasmResponse(String responseJson) throws Exception {
        JsonNode root = mapper.readTree(responseJson);
        String status = root.path("status").asText();
        if (!"success".equals(status)) {
            throw new RuntimeException(root.path("error_message").asText("Unknown WASM error"));
        }
        return root.path("result");
    }

    private String mapReason(String fliptReason) {
        if (fliptReason == null) {
            return Reason.UNKNOWN.name();
        }
        return switch (fliptReason) {
            case "MATCH_EVALUATION_REASON" -> Reason.TARGETING_MATCH.name();
            case "FLAG_DISABLED_EVALUATION_REASON" -> Reason.DISABLED.name();
            case "DEFAULT_EVALUATION_REASON" -> Reason.DEFAULT.name();
            default -> Reason.UNKNOWN.name();
        };
    }

    private Value parseJsonValue(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return jsonNodeToValue(node);
        } catch (Exception e) {
            return new Value(json);
        }
    }

    private Value jsonNodeToValue(JsonNode node) {
        if (node.isBoolean()) {
            return new Value(node.asBoolean());
        } else if (node.isInt() || node.isLong()) {
            return new Value(node.asInt());
        } else if (node.isDouble() || node.isFloat()) {
            return new Value(node.asDouble());
        } else if (node.isTextual()) {
            return new Value(node.asText());
        } else if (node.isObject()) {
            Map<String, Value> map = new HashMap<>();
            node.forEachEntry((key, value) -> map.put(key, jsonNodeToValue(value)));
            return new Value(new ImmutableStructure(map));
        } else if (node.isArray()) {
            List<Value> list = new ArrayList<>();
            for (JsonNode child : node) {
                list.add(jsonNodeToValue(child));
            }
            return new Value(list);
        }
        return new Value(node.asText());
    }

    @Override
    public Collection<FlagInfo> getFlags() {
        try {
            String snapshot = enginePool.getLatestSnapshot();
            JsonNode root = mapper.readTree(snapshot);
            JsonNode flags = root.get("flags");
            if (flags == null || !flags.isArray()) {
                return List.of();
            }
            List<FlagInfo> result = new ArrayList<>();
            for (JsonNode flag : flags) {
                JsonNode key = flag.get("key");
                if (key != null) {
                    FlagValueType type = null;
                    JsonNode typeNode = flag.get("type");
                    if (typeNode != null && "BOOLEAN_FLAG_TYPE".equals(typeNode.asText())) {
                        type = FlagValueType.BOOLEAN;
                    } else if (typeNode != null && "VARIANT_FLAG_TYPE".equals(typeNode.asText())) {
                        type = FlagValueType.STRING;
                    }
                    result.add(new FlagInfo(key.asText(), type));
                }
            }
            return result;
        } catch (Exception e) {
            log.debugf(e, "Failed to parse flags from snapshot");
            return List.of();
        }
    }
}
