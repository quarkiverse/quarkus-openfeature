package io.quarkiverse.openfeature.gofeatureflag.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import io.quarkiverse.openfeature.runtime.SyncClientState;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

// Concurrency: flag data is updated from the Vert.x event loop and evaluated
// from request threads. This is safe because each evaluation acquires a WASM
// engine instance from a pool, and each instance is used by one thread at a time.
public class GoFeatureFlagFeatureProvider extends AbstractRemoteFeatureProvider {
    private static final Logger log = Logger.getLogger(GoFeatureFlagFeatureProvider.class);

    private final ObjectMapper mapper;
    private final GoFeatureFlagWasmEnginePool enginePool;
    private final GoFeatureFlagSyncClient syncClient;

    public GoFeatureFlagFeatureProvider(ObjectMapper mapper, GoFeatureFlagWasmEnginePool enginePool,
            Vertx vertx, GoFeatureFlagConfig.ProviderConfig config,
            TlsConfigurationRegistry tlsRegistry, String apiKey) {
        this(mapper, enginePool, vertx, config, tlsRegistry, apiKey, new SyncClientState(vertx));
    }

    private GoFeatureFlagFeatureProvider(ObjectMapper mapper, GoFeatureFlagWasmEnginePool enginePool,
            Vertx vertx, GoFeatureFlagConfig.ProviderConfig config,
            TlsConfigurationRegistry tlsRegistry, String apiKey, SyncClientState syncState) {
        super(vertx, config.gracePeriod(), syncState);
        this.mapper = mapper;
        this.enginePool = enginePool;
        this.syncClient = new GoFeatureFlagSyncClient(mapper, vertx, context(), config, tlsRegistry, apiKey, syncState);
    }

    @Override
    public Metadata getMetadata() {
        return () -> "gofeatureflag";
    }

    @Override
    public void initialize(EvaluationContext evaluationContext) throws Exception {
        syncClient.start(new GoFeatureFlagSyncClient.Listener() {
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
            public void onConfigurationChanged(Set<String> changedKeys) {
                if (syncClient.isShutdown()) {
                    return;
                }
                handleConfigurationChanged("flags updated via SSE", new ArrayList<>(changedKeys));
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
        ProviderEvaluation<Boolean> override = evaluateFlagOverride(key, Boolean.class);
        if (override != null) {
            return override;
        }
        return evaluate(key, defaultValue, Boolean.class, ctx);
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<String> override = evaluateFlagOverride(key, String.class);
        if (override != null) {
            return override;
        }
        return evaluate(key, defaultValue, String.class, ctx);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Integer> override = evaluateFlagOverride(key, Integer.class);
        if (override != null) {
            return override;
        }
        return evaluate(key, defaultValue, Integer.class, ctx);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Double> override = evaluateFlagOverride(key, Double.class);
        if (override != null) {
            return override;
        }
        return evaluate(key, defaultValue, Double.class, ctx);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Value> override = evaluateFlagOverride(key, Value.class);
        if (override != null) {
            return override;
        }
        return evaluate(key, defaultValue, Value.class, ctx);
    }

    private <T> ProviderEvaluation<T> evaluate(String key, T defaultValue, Class<T> expectedType, EvaluationContext ctx) {
        JsonNode flagDef = syncClient.getFlag(key);
        if (flagDef == null) {
            return ProviderEvaluation.<T> builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.name())
                    .errorCode(ErrorCode.FLAG_NOT_FOUND)
                    .errorMessage("Flag " + key + " was not found in your configuration")
                    .build();
        }

        try {
            String inputJson = buildWasmInput(key, flagDef, defaultValue, ctx);
            String outputJson = enginePool.evaluate(inputJson);
            JsonNode response = mapper.readTree(outputJson);

            if (response.path("failed").asBoolean(false)) {
                return ProviderEvaluation.<T> builder()
                        .value(defaultValue)
                        .reason(Reason.ERROR.name())
                        .errorCode(mapErrorCode(response.path("errorCode").asText("GENERAL")))
                        .errorMessage(response.path("errorDetails").asText("Unknown error"))
                        .build();
            }

            return ProviderEvaluation.<T> builder()
                    .value(convertValue(response.get("value"), expectedType, defaultValue))
                    .variant(response.path("variationType").asText(null))
                    .reason(response.path("reason").asText(Reason.UNKNOWN.name()))
                    .build();
        } catch (Exception e) {
            return ProviderEvaluation.<T> builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.name())
                    .errorCode(ErrorCode.GENERAL)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private String buildWasmInput(String flagKey, JsonNode flagDef, Object defaultValue,
            EvaluationContext ctx) throws Exception {
        ObjectNode input = mapper.createObjectNode();
        input.put("flagKey", flagKey);
        input.set("flag", flagDef);

        if (ctx != null) {
            input.set("evalContext", mapper.valueToTree(ctx.asObjectMap()));
        } else {
            input.set("evalContext", mapper.createObjectNode());
        }

        ObjectNode flagContext = mapper.createObjectNode();
        if (defaultValue != null) {
            flagContext.set("defaultSdkValue", mapper.valueToTree(defaultValue));
        }
        Map<String, Object> enrichment = syncClient.getEvaluationContextEnrichment();
        if (enrichment != null) {
            flagContext.set("evaluationContextEnrichment", mapper.valueToTree(enrichment));
        }
        input.set("flagContext", flagContext);

        return mapper.writeValueAsString(input);
    }

    private ErrorCode mapErrorCode(String code) {
        if (code == null) {
            return ErrorCode.GENERAL;
        }
        return switch (code) {
            case "FLAG_NOT_FOUND" -> ErrorCode.FLAG_NOT_FOUND;
            case "TYPE_MISMATCH" -> ErrorCode.TYPE_MISMATCH;
            case "PARSE_ERROR" -> ErrorCode.PARSE_ERROR;
            case "TARGETING_KEY_MISSING" -> ErrorCode.TARGETING_KEY_MISSING;
            case "INVALID_CONTEXT" -> ErrorCode.INVALID_CONTEXT;
            default -> ErrorCode.GENERAL;
        };
    }

    @SuppressWarnings("unchecked")
    private <T> T convertValue(JsonNode valueNode, Class<T> expectedType, T defaultValue) {
        if (valueNode == null || valueNode.isNull()) {
            return defaultValue;
        }
        if (expectedType == Boolean.class) {
            return (T) Boolean.valueOf(valueNode.asBoolean());
        } else if (expectedType == String.class) {
            return (T) valueNode.asText();
        } else if (expectedType == Integer.class) {
            return (T) Integer.valueOf(valueNode.asInt());
        } else if (expectedType == Double.class) {
            return (T) Double.valueOf(valueNode.asDouble());
        } else if (expectedType == Value.class) {
            return (T) jsonNodeToValue(valueNode);
        }
        return defaultValue;
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
            Map<String, JsonNode> flags = syncClient.getAllFlags();
            List<FlagInfo> result = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : flags.entrySet()) {
                FlagValueType type = null;
                JsonNode typeNode = entry.getValue().get("type");
                if (typeNode != null) {
                    String typeStr = typeNode.asText();
                    if ("boolean".equalsIgnoreCase(typeStr)) {
                        type = FlagValueType.BOOLEAN;
                    } else if ("string".equalsIgnoreCase(typeStr)) {
                        type = FlagValueType.STRING;
                    } else if ("integer".equalsIgnoreCase(typeStr) || "int".equalsIgnoreCase(typeStr)) {
                        type = FlagValueType.INTEGER;
                    } else if ("double".equalsIgnoreCase(typeStr) || "float".equalsIgnoreCase(typeStr)) {
                        type = FlagValueType.DOUBLE;
                    }
                }
                result.add(new FlagInfo(entry.getKey(), type));
            }
            return result;
        } catch (Exception e) {
            log.debugf(e, "Failed to list flags");
            return List.of();
        }
    }
}
