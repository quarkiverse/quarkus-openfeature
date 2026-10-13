package io.quarkiverse.openfeature.gofeatureflag.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import dev.openfeature.sdk.exceptions.GeneralError;
import dev.openfeature.sdk.exceptions.ParseError;
import dev.openfeature.sdk.exceptions.TypeMismatchError;
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

        String inputJson = buildWasmInput(key, flagDef, defaultValue, ctx);
        String outputJson = enginePool.evaluate(inputJson);
        JsonNode response;
        try {
            response = mapper.readTree(outputJson);
        } catch (JsonProcessingException e) {
            throw new ParseError("Wrong response from the GO Feature Flag WASM engine: " + e.getMessage());
        }

        // the engine reports an evaluation failure with its own error code, which is more
        // specific than anything that could be derived from an exception
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
    }

    private String buildWasmInput(String flagKey, JsonNode flagDef, Object defaultValue,
            EvaluationContext ctx) {
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

        try {
            return mapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new GeneralError("Failed to build the GO Feature Flag evaluation request: " + e.getMessage());
        }
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

    // No `Long.class` branch on purpose: `getLongEvaluation` is not overridden, so long
    // requests take the SDK default path through `getDoubleEvaluation`. A native long path
    // would not widen the supported range anyway, because the WASM engine holds every number
    // as a float64 and so already rounds values outside [-(2^53 - 1), 2^53 - 1].
    // Every branch checks the JSON type before converting, because the `asSomething` methods
    // coerce instead of failing: `asText()` on an object or array is "" and `asInt()` on a
    // string is 0, either of which would quietly replace the caller's default with a value
    // the flag never had. A type mismatch is an error the SDK reports.
    @SuppressWarnings("unchecked")
    private <T> T convertValue(JsonNode valueNode, Class<T> expectedType, T defaultValue) {
        if (valueNode == null || valueNode.isNull()) {
            return defaultValue;
        }
        if (expectedType == Boolean.class) {
            if (!valueNode.isBoolean()) {
                throw typeMismatch(valueNode, "a boolean");
            }
            return (T) Boolean.valueOf(valueNode.booleanValue());
        } else if (expectedType == String.class) {
            if (!valueNode.isTextual()) {
                throw typeMismatch(valueNode, "a string");
            }
            return (T) valueNode.textValue();
        } else if (expectedType == Integer.class) {
            // an integral number too large for an `int` is a mismatch as well, because
            // truncating it would be just as silent as coercing a string to 0
            if (!valueNode.isIntegralNumber() || !valueNode.canConvertToInt()) {
                throw typeMismatch(valueNode, "an integer");
            }
            return (T) Integer.valueOf(valueNode.intValue());
        } else if (expectedType == Double.class) {
            // `long` requests arrive here through the SDK default path, so any number goes
            if (!valueNode.isNumber()) {
                throw typeMismatch(valueNode, "a number");
            }
            return (T) Double.valueOf(valueNode.doubleValue());
        } else if (expectedType == Value.class) {
            return (T) jsonNodeToValue(valueNode);
        }
        return defaultValue;
    }

    private static TypeMismatchError typeMismatch(JsonNode valueNode, String expected) {
        return new TypeMismatchError("Flag value " + valueNode + " is not " + expected);
    }

    // Deliberately duplicated in FliptFeatureProvider; the two providers share no
    // module and core/runtime has no Jackson dependency. Fix both copies, never just one.
    static Value jsonNodeToValue(JsonNode node) {
        if (node.isBoolean()) {
            return new Value(node.asBoolean());
        } else if (node.isInt()) {
            return new Value(node.asInt());
        } else if (node.isLong()) {
            return new Value(node.asLong());
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
            return parseFlags(syncClient.getAllFlags());
        } catch (Exception e) {
            log.debugf(e, "Failed to list flags");
            return List.of();
        }
    }

    // the flags of the latest configuration are the single source of truth, so that flags
    // deleted on the server disappear from the list instead of lingering forever
    static Collection<FlagInfo> parseFlags(Map<String, JsonNode> flags) {
        if (flags == null) {
            return List.of();
        }
        List<FlagInfo> result = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : flags.entrySet()) {
            result.add(new FlagInfo(entry.getKey(), inferType(entry.getValue())));
        }
        return result;
    }

    // A flag definition carries no declared type; the flag configuration endpoint returns the
    // raw flag exactly as it was authored, so the type has to come from the variation values.
    // GO Feature Flag requires all variations of a flag to have the same type
    // (`InternalFlag.IsValid`), and variations that disagree have no type to report.
    //
    // Deliberately duplicated in FlagdFeatureProvider, whose schema says the same thing about
    // its variants. The two providers share no module. Fix both copies, never just one.
    static FlagValueType inferType(JsonNode flagNode) {
        JsonNode variations = flagNode.get("variations");
        if (variations == null || !variations.isObject() || variations.isEmpty()) {
            return null;
        }

        JsonNode first = variations.iterator().next();
        if (first.isBoolean()) {
            return allMatch(variations, JsonNode::isBoolean) ? FlagValueType.BOOLEAN : null;
        } else if (first.isNumber()) {
            return numberType(variations);
        } else if (first.isTextual()) {
            return allMatch(variations, JsonNode::isTextual) ? FlagValueType.STRING : null;
        } else if (first.isContainerNode()) {
            return allMatch(variations, JsonNode::isContainerNode) ? FlagValueType.OBJECT : null;
        }
        return null;
    }

    private static boolean allMatch(JsonNode values, Predicate<JsonNode> predicate) {
        for (JsonNode value : values) {
            if (!predicate.test(value)) {
                return false;
            }
        }
        return true;
    }

    // The type that all variations share is the JSON one, where `1` and `3.14` are both
    // numbers, so the widest variation decides. Reporting an integer for `{1, 3.14}` would
    // have the Dev UI resolve `3.14` as an `int` and show `3`.
    private static FlagValueType numberType(JsonNode variations) {
        boolean integral = true;
        boolean fitsInInt = true;
        for (JsonNode value : variations) {
            if (!value.isNumber()) {
                return null;
            }
            integral &= value.isIntegralNumber();
            fitsInInt &= value.canConvertToInt();
        }
        if (!integral) {
            return FlagValueType.DOUBLE;
        }
        return fitsInInt ? FlagValueType.INTEGER : FlagValueType.LONG;
    }
}
