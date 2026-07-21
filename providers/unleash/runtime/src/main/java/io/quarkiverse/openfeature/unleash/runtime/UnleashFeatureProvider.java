package io.quarkiverse.openfeature.unleash.runtime;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagValueType;
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

// Concurrency: the UnleashEngine is updated from the Vert.x event loop
// and read from request threads. This is safe because the native Yggdrasil
// engine uses a Mutex internally.
public class UnleashFeatureProvider extends AbstractRemoteFeatureProvider {
    private static final Logger log = Logger.getLogger(UnleashFeatureProvider.class);

    private final UnleashSyncClient syncClient;
    private final UnleashEngine engine;
    private final String appName;
    private final String environment;

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
            return (T) new Value(payloadValue);
        }
        return defaultValue;
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
            for (FeatureDef toggle : toggles) {
                FlagValueType type = null;
                if (toggle.getType().isPresent()) {
                    String typeStr = toggle.getType().get();
                    if ("release".equalsIgnoreCase(typeStr) || "kill-switch".equalsIgnoreCase(typeStr)) {
                        type = FlagValueType.BOOLEAN;
                    } else if ("experiment".equalsIgnoreCase(typeStr)) {
                        type = FlagValueType.STRING;
                    }
                }
                result.add(new FlagInfo(toggle.getName(), type));
            }
            return result;
        } catch (Exception e) {
            log.debugf(e, "Failed to list flags");
            return List.of();
        }
    }
}
