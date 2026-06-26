package io.quarkiverse.openfeature.flagd.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.openfeature.contrib.tools.flagd.api.Evaluator;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import io.quarkiverse.openfeature.runtime.AbstractRemoteFeatureProvider;
import io.quarkiverse.openfeature.runtime.DevFeatureAccess.FlagInfo;
import io.quarkiverse.openfeature.runtime.SyncClientState;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;

// Concurrency: the Evaluator (FlagdCore) is updated from the Vert.x event loop
// and read from request threads. This is safe because FlagdCore uses a
// ReentrantReadWriteLock internally.
public class FlagdFeatureProvider extends AbstractRemoteFeatureProvider {
    private static final Logger log = Logger.getLogger(FlagdFeatureProvider.class);

    private final ObjectMapper mapper;
    private final Evaluator evaluator;
    private final FlagdSyncClient syncClient;
    private final Set<String> knownFlagKeys = ConcurrentHashMap.newKeySet();

    private volatile EvaluationContext syncContext;

    // `public` only for the `e2e-tests`
    public FlagdFeatureProvider(ObjectMapper mapper, Evaluator evaluator, Vertx vertx,
            FlagdConfig.ProviderConfig config, TlsConfigurationRegistry tlsRegistry) {
        this(mapper, evaluator, vertx, config, tlsRegistry, new SyncClientState(vertx));
    }

    private FlagdFeatureProvider(ObjectMapper mapper, Evaluator evaluator,
            Vertx vertx, FlagdConfig.ProviderConfig config, TlsConfigurationRegistry tlsRegistry,
            SyncClientState syncState) {
        super(vertx, config.gracePeriod(), syncState);
        this.mapper = mapper;
        this.evaluator = evaluator;
        this.syncClient = new FlagdSyncClient(vertx, context(), config, evaluator, tlsRegistry, syncState);
    }

    @Override
    public Metadata getMetadata() {
        return () -> "flagd";
    }

    @Override
    public List<Hook> getProviderHooks() {
        return List.of(new SyncMetadataHook(() -> syncContext));
    }

    @Override
    public void initialize(EvaluationContext evaluationContext) throws Exception {
        syncClient.start(new FlagdSyncClient.Listener() {
            @Override
            public void onUpdate(List<String> changedKeys, EvaluationContext newSyncContext, boolean reconnected) {
                if (syncClient.isShutdown()) {
                    return;
                }
                knownFlagKeys.addAll(changedKeys);
                if (newSyncContext != null) {
                    syncContext = newSyncContext;
                }
                if (reconnected) {
                    handleReconnected(changedKeys);
                } else {
                    handleConfigurationChanged("flags updated", changedKeys);
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
        ProviderEvaluation<Boolean> override = evaluateTestOverride(key, Boolean.class);
        if (override != null) {
            return override;
        }
        return evaluator.resolveBooleanValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<String> override = evaluateTestOverride(key, String.class);
        if (override != null) {
            return override;
        }
        return evaluator.resolveStringValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Integer> override = evaluateTestOverride(key, Integer.class);
        if (override != null) {
            return override;
        }
        return evaluator.resolveIntegerValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Double> override = evaluateTestOverride(key, Double.class);
        if (override != null) {
            return override;
        }
        return evaluator.resolveDoubleValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Value> override = evaluateTestOverride(key, Value.class);
        if (override != null) {
            return override;
        }
        return evaluator.resolveObjectValue(key, defaultValue, ctx);
    }

    @Override
    public Collection<FlagInfo> getFlags() {
        String json = syncClient.getLatestFlagConfiguration();
        if (json != null) {
            try {
                JsonNode root = mapper.readTree(json);
                JsonNode flags = root.get("flags");
                if (flags == null || !flags.isObject()) {
                    return List.of();
                }
                List<FlagInfo> result = new ArrayList<>();
                for (Map.Entry<String, JsonNode> property : flags.properties()) {
                    result.add(new FlagInfo(property.getKey(), inferType(property.getValue())));
                }
                return result;
            } catch (Exception e) {
                log.debugf(e, "Failed to parse flags from configuration");
            }
        }

        List<FlagInfo> result = new ArrayList<>();
        for (String key : knownFlagKeys) {
            FlagInfo flagInfo = new FlagInfo(key, null);
            result.add(flagInfo);
        }
        return result;
    }

    private static FlagValueType inferType(JsonNode flagNode) {
        JsonNode defaultVariant = flagNode.get("defaultVariant");
        JsonNode variants = flagNode.get("variants");
        if (defaultVariant == null || variants == null) {
            return null;
        }
        JsonNode value = variants.get(defaultVariant.asText());
        if (value == null) {
            return null;
        }
        if (value.isBoolean()) {
            return FlagValueType.BOOLEAN;
        } else if (value.isInt()) {
            return FlagValueType.INTEGER;
        } else if (value.isNumber()) {
            return FlagValueType.DOUBLE;
        } else if (value.isTextual()) {
            return FlagValueType.STRING;
        } else if (value.isObject() || value.isArray()) {
            return FlagValueType.OBJECT;
        }
        return null;
    }
}
