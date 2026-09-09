package io.quarkiverse.openfeature.unleash.runtime;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import io.getunleash.engine.Context;
import io.getunleash.engine.FeatureDef;
import io.getunleash.engine.FlatResponse;
import io.getunleash.engine.Payload;
import io.getunleash.engine.UnleashEngine;
import io.getunleash.engine.VariantDef;
import io.getunleash.engine.YggdrasilInvalidInputException;
import io.quarkiverse.openfeature.runtime.AbstractRemoteFeatureProvider;
import io.quarkiverse.openfeature.runtime.SyncClientState;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

// Concurrency: the UnleashEngine is updated from the Vert.x event loop
// and read from request threads. This is safe because the native Yggdrasil
// engine uses a Mutex internally.
public class UnleashFeatureProvider extends AbstractRemoteFeatureProvider {
    private static final Logger log = Logger.getLogger(UnleashFeatureProvider.class);

    private final UnleashSyncClient syncClient;
    private final UnleashEngine engine;
    private final String appName;
    private final String environment;
    // the Unleash server serves its management console next to the API, so `.../api` becomes `...`
    private final String consoleUrl;
    // the engine only exposes flag names, so the value types are derived from the raw flag
    // data; written on the event loop when flags are synced, read from request threads
    private volatile Map<String, FlagValueType> flagTypes = Map.of();

    public UnleashFeatureProvider(UnleashEngine engine, Vertx vertx,
            UnleashConfig.ProviderConfig config, TlsConfigurationRegistry tlsRegistry,
            String apiKey, String appName, String environment) {
        this(engine, vertx, config, tlsRegistry, apiKey, appName, environment, new SyncClientState(vertx));
    }

    private UnleashFeatureProvider(UnleashEngine engine, Vertx vertx,
            UnleashConfig.ProviderConfig config, TlsConfigurationRegistry tlsRegistry,
            String apiKey, String appName, String environment, SyncClientState syncState) {
        super(vertx, config.gracePeriod(), syncState);
        this.syncClient = new UnleashSyncClient(vertx, context(), config, tlsRegistry, apiKey, syncState);
        this.engine = engine;
        this.appName = appName;
        this.environment = environment;
        this.consoleUrl = consoleUrl(config.url());
    }

    static String consoleUrl(String url) {
        String result = url;
        if (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.endsWith("/api")) {
            result = result.substring(0, result.length() - "/api".length());
        }
        return result;
    }

    @Override
    public Metadata getMetadata() {
        return () -> "unleash";
    }

    @Override
    public void initialize(EvaluationContext evaluationContext) throws Exception {
        syncClient.start(new UnleashSyncClient.Listener() {
            @Override
            public void onUpdate(String features, boolean reconnected) throws YggdrasilInvalidInputException {
                engine.takeState(features);
                flagTypes = parseFlagTypes(features);
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
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Boolean> override = evaluateFlagOverride(key, Boolean.class);
        if (override != null) {
            return override;
        }

        try {
            Context context = mapContext(ctx);
            FlatResponse<Boolean> response = engine.isEnabled(key, context);
            if (response.value == null) {
                return ProviderEvaluation.<Boolean> builder()
                        .value(defaultValue)
                        .reason(Reason.ERROR.name())
                        .errorCode(ErrorCode.FLAG_NOT_FOUND)
                        .errorMessage("Flag " + key + " was not found")
                        .build();
            }
            return ProviderEvaluation.<Boolean> builder()
                    .value(response.value)
                    .reason(Reason.TARGETING_MATCH.name())
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
        ProviderEvaluation<String> override = evaluateFlagOverride(key, String.class);
        if (override != null) {
            return override;
        }
        return evaluateVariant(key, defaultValue, String.class, ctx);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Integer> override = evaluateFlagOverride(key, Integer.class);
        if (override != null) {
            return override;
        }
        return evaluateVariant(key, defaultValue, Integer.class, ctx);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Double> override = evaluateFlagOverride(key, Double.class);
        if (override != null) {
            return override;
        }
        return evaluateVariant(key, defaultValue, Double.class, ctx);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Value> override = evaluateFlagOverride(key, Value.class);
        if (override != null) {
            return override;
        }
        return evaluateVariant(key, defaultValue, Value.class, ctx);
    }

    private <T> ProviderEvaluation<T> evaluateVariant(String key, T defaultValue, Class<T> expectedType,
            EvaluationContext ctx) {
        try {
            Context context = mapContext(ctx);
            FlatResponse<VariantDef> response = engine.getVariant(key, context);
            if (response.value == null) {
                return ProviderEvaluation.<T> builder()
                        .value(defaultValue)
                        .reason(Reason.ERROR.name())
                        .errorCode(ErrorCode.FLAG_NOT_FOUND)
                        .errorMessage("Flag " + key + " was not found")
                        .build();
            }

            VariantDef variant = response.value;
            if (!variant.isFeatureEnabled()) {
                return ProviderEvaluation.<T> builder()
                        .value(defaultValue)
                        .variant(variant.getName())
                        .reason(Reason.DISABLED.name())
                        .build();
            }

            return ProviderEvaluation.<T> builder()
                    .value(extractVariantValue(variant, expectedType, defaultValue))
                    .variant(variant.getName())
                    .reason(Reason.TARGETING_MATCH.name())
                    .build();
        } catch (NumberFormatException e) {
            return ProviderEvaluation.<T> builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.name())
                    .errorCode(ErrorCode.TYPE_MISMATCH)
                    .errorMessage(e.getMessage())
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

    @SuppressWarnings("unchecked")
    private <T> T extractVariantValue(VariantDef variant, Class<T> expectedType, T defaultValue) {
        Payload payload = variant.getPayload();
        if (payload == null || payload.getValue() == null) {
            if (expectedType == String.class) {
                return (T) variant.getName();
            }
            return defaultValue;
        }

        String payloadValue = payload.getValue();

        if (expectedType == String.class) {
            return (T) payloadValue;
        } else if (expectedType == Integer.class) {
            return (T) Integer.valueOf(payloadValue);
        } else if (expectedType == Double.class) {
            return (T) Double.valueOf(payloadValue);
        } else if (expectedType == Value.class) {
            return (T) payloadValue(payload);
        }
        return defaultValue;
    }

    // The declared payload type is authoritative: `json` becomes a structure and `number`
    // becomes a number, while `string` and `csv` are text. A payload whose value doesn't
    // match its declared type is returned as text as well, which is more useful than
    // failing the evaluation outright.
    static Value payloadValue(Payload payload) {
        String type = payload.getType();
        if ("json".equalsIgnoreCase(type)) {
            try {
                return jsonValue(Json.decodeValue(payload.getValue()));
            } catch (DecodeException e) {
                return new Value(payload.getValue());
            }
        } else if ("number".equalsIgnoreCase(type)) {
            Value number = numberValue(payload.getValue());
            if (number != null) {
                return number;
            }
        }
        return new Value(payload.getValue());
    }

    // the narrowest type that holds the value exactly, so that the resolved value agrees
    // with the type that `getFlags` reports for the same payload
    private static Value numberValue(String value) {
        try {
            return new Value(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            // not an int, try wider
        }
        try {
            return new Value(Long.parseLong(value));
        } catch (NumberFormatException e) {
            // not a long, try wider
        }
        try {
            return new Value(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Value jsonValue(Object json) {
        if (json == null) {
            return new Value();
        } else if (json instanceof JsonObject object) {
            Map<String, Value> map = new HashMap<>();
            for (Map.Entry<String, Object> entry : object) {
                map.put(entry.getKey(), jsonValue(entry.getValue()));
            }
            return new Value(new ImmutableStructure(map));
        } else if (json instanceof JsonArray array) {
            List<Value> list = new ArrayList<>(array.size());
            for (Object item : array) {
                list.add(jsonValue(item));
            }
            return new Value(list);
        } else if (json instanceof Boolean bool) {
            return new Value(bool);
        } else if (json instanceof Integer integer) {
            return new Value(integer);
        } else if (json instanceof Long number) {
            return new Value(number);
        } else if (json instanceof Number number) {
            return new Value(number.doubleValue());
        }
        return new Value(json.toString());
    }

    private Context mapContext(EvaluationContext ctx) {
        Context context = new Context();
        if (ctx == null) {
            return context;
        }

        if (ctx.getTargetingKey() != null) {
            context.setUserId(ctx.getTargetingKey());
        }

        Map<String, String> properties = new HashMap<>();
        ctx.asMap().forEach((key, value) -> {
            if (value == null) {
                return;
            }
            switch (key) {
                // static
                case "appName" -> context.setAppName(value.asString());
                case "environment" -> context.setEnvironment(value.asString());

                // dynamic
                case "targetingKey" -> {
                    // already set above
                }
                case "sessionId" -> context.setSessionId(value.asString());
                case "remoteAddress" -> context.setRemoteAddress(value.asString());
                case "currentTime" -> context.setCurrentTime(value.asString());

                // additional
                default -> properties.put(key, value.asString());
            }
        });
        if (!properties.isEmpty()) {
            context.setProperties(properties);
        }

        if (context.getAppName() == null && appName != null) {
            context.setAppName(appName);
        }
        if (context.getEnvironment() == null && environment != null) {
            context.setEnvironment(environment);
        }
        if (context.getCurrentTime() == null) {
            context.setCurrentTime(OffsetDateTime.now().toString());
        }

        return context;
    }

    @Override
    public Collection<FlagInfo> getFlags() {
        try {
            List<FeatureDef> toggles = engine.listKnownToggles();
            List<FlagInfo> result = new ArrayList<>();
            Map<String, FlagValueType> types = flagTypes;
            for (FeatureDef toggle : toggles) {
                result.add(new FlagInfo(toggle.getName(), types.get(toggle.getName())));
            }
            return result;
        } catch (Exception e) {
            log.debugf(e, "Failed to list flags");
            return List.of();
        }
    }

    // `FeatureDef.getType()` is the Unleash flag type (release, experiment, kill-switch, ...),
    // a lifecycle classification that says nothing about the value type. The value type follows
    // from the variants instead: a flag without variants is evaluated through `isEnabled` and is
    // therefore boolean, while a flag with variants carries its type in the variant payload.
    static Map<String, FlagValueType> parseFlagTypes(String featuresJson) {
        JsonArray features = new JsonObject(featuresJson).getJsonArray("features");
        if (features == null) {
            return Map.of();
        }

        Map<String, FlagValueType> result = new HashMap<>();
        for (int i = 0; i < features.size(); i++) {
            JsonObject feature = features.getJsonObject(i);
            String name = feature.getString("name");
            if (name != null) {
                result.put(name, flagType(variantsOf(feature)));
            }
        }
        return result;
    }

    // Variants can be attached to the flag itself or to one of its strategies. A real Unleash
    // server puts everything created through the admin UI in the latter place and leaves the
    // flag-level array empty, so both have to be taken into account.
    private static List<JsonObject> variantsOf(JsonObject feature) {
        List<JsonObject> result = new ArrayList<>();
        addAll(result, feature.getJsonArray("variants"));

        JsonArray strategies = feature.getJsonArray("strategies");
        if (strategies != null) {
            for (int i = 0; i < strategies.size(); i++) {
                addAll(result, strategies.getJsonObject(i).getJsonArray("variants"));
            }
        }
        return result;
    }

    private static void addAll(List<JsonObject> result, JsonArray variants) {
        if (variants == null) {
            return;
        }
        for (int i = 0; i < variants.size(); i++) {
            result.add(variants.getJsonObject(i));
        }
    }

    private static FlagValueType flagType(List<JsonObject> variants) {
        if (variants.isEmpty()) {
            return FlagValueType.BOOLEAN;
        }

        // a variant without a payload evaluates to its own name, which is a string
        String payloadType = null;
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            JsonObject payload = variants.get(i).getJsonObject("payload");
            String type = payload != null ? payload.getString("type", "string") : "string";
            if (i == 0) {
                payloadType = type;
            } else if (!payloadType.equals(type)) {
                // the variants of one flag are expected to agree; if they don't, there is
                // no single type to report
                return null;
            }
            if (payload != null) {
                numbers.add(payload.getString("value"));
            }
        }

        return switch (payloadType) {
            case "string", "csv" -> FlagValueType.STRING;
            case "json" -> FlagValueType.OBJECT;
            case "number" -> numberType(numbers);
            default -> null;
        };
    }

    // Unleash stores number payloads as strings, so the type is the narrowest one that
    // holds every variant exactly, the same way `ConfigFeatureProvider` infers it
    private static FlagValueType numberType(List<String> values) {
        boolean allInt = true;
        boolean allLong = true;
        for (String value : values) {
            if (allInt) {
                try {
                    Integer.parseInt(value);
                    continue;
                } catch (NumberFormatException e) {
                    allInt = false;
                }
            }
            if (allLong) {
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException e) {
                    allLong = false;
                }
            }
        }

        if (allInt) {
            return FlagValueType.INTEGER;
        }
        if (allLong) {
            return FlagValueType.LONG;
        }
        return FlagValueType.DOUBLE;
    }

    @Override
    public Optional<String> getConsoleUrl() {
        return Optional.of(consoleUrl);
    }
}
