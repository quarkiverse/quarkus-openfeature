package io.quarkiverse.openfeature.flagd.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
//
// TODO when flagd-core Evaluator adds `resolveLongValue`:
// - override `getLongEvaluation` here to call `evaluator.resolveLongValue`
// - update test value in FlagdTest and flags.json (deployment + integration-tests)
//   to use a value > Integer.MAX_VALUE (e.g. 10_000_000_000)
public class FlagdFeatureProvider extends AbstractRemoteFeatureProvider {
    private static final Logger log = Logger.getLogger(FlagdFeatureProvider.class);

    private final ObjectMapper mapper;
    private final Evaluator evaluator;
    private final FlagdSyncClient syncClient;

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
        ProviderEvaluation<Boolean> override = evaluateFlagOverride(key, Boolean.class);
        if (override != null) {
            return override;
        }
        return evaluator.resolveBooleanValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<String> override = evaluateFlagOverride(key, String.class);
        if (override != null) {
            return override;
        }
        return evaluator.resolveStringValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Integer> override = evaluateFlagOverride(key, Integer.class);
        if (override != null) {
            return override;
        }
        return evaluator.resolveIntegerValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Double> override = evaluateFlagOverride(key, Double.class);
        if (override != null) {
            return override;
        }
        return evaluator.resolveDoubleValue(key, defaultValue, ctx);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        ProviderEvaluation<Value> override = evaluateFlagOverride(key, Value.class);
        if (override != null) {
            return override;
        }
        return evaluator.resolveObjectValue(key, defaultValue, ctx);
    }

    @Override
    public Collection<FlagInfo> getFlags() {
        return parseFlags(mapper, syncClient.getLatestFlagConfiguration());
    }

    // the latest configuration is the single source of truth, so that flags deleted
    // on the server disappear from the list instead of lingering forever
    static Collection<FlagInfo> parseFlags(ObjectMapper mapper, String json) {
        if (json == null) {
            return List.of();
        }
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
            return List.of();
        }
    }

    // The type comes from all the variants, not just the default one: the schema makes a flag
    // a boolean, number, string or object flag and requires every variant to match, and any
    // of them may be the one an evaluation resolves. Variants that disagree have no type to
    // report.
    //
    // Deliberately duplicated in GoFeatureFlagFeatureProvider, which says the same thing about
    // its variations. The two providers share no module. Fix both copies, never just one.
    private static FlagValueType inferType(JsonNode flagNode) {
        JsonNode variants = flagNode.get("variants");
        if (variants == null || !variants.isObject() || variants.isEmpty()) {
            return null;
        }

        JsonNode first = variants.iterator().next();
        if (first.isBoolean()) {
            return allMatch(variants, JsonNode::isBoolean) ? FlagValueType.BOOLEAN : null;
        } else if (first.isNumber()) {
            return numberType(variants);
        } else if (first.isTextual()) {
            return allMatch(variants, JsonNode::isTextual) ? FlagValueType.STRING : null;
        } else if (first.isContainerNode()) {
            return allMatch(variants, JsonNode::isContainerNode) ? FlagValueType.OBJECT : null;
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

    // The schema says `number`, which covers both `1` and `3.14`, so the widest variant
    // decides. Reporting an integer for `{1, 3.14}` would have the Dev UI resolve `3.14`
    // as an `int` and show `3`.
    private static FlagValueType numberType(JsonNode variants) {
        boolean integral = true;
        boolean fitsInInt = true;
        for (JsonNode value : variants) {
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
