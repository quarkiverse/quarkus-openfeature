package io.quarkiverse.openfeature.runtime.devui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import io.quarkiverse.openfeature.runtime.DevFeatureAccess;
import io.quarkiverse.openfeature.runtime.FlagOverrides;
import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig;
import io.quarkiverse.openfeature.runtime.OpenFeatureRecorder;
import io.quarkiverse.openfeature.runtime.OverrideFeatureAccess;
import io.smallrye.common.annotation.NonBlocking;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@ApplicationScoped
public class OpenFeatureJsonRpcService {
    private final Map<String, FlagOverrides> devOverrides = new HashMap<>();

    @NonBlocking
    public JsonObject getProviderStatus(String domain) {
        Client client = getClient(domain);
        JsonArray providers = new JsonArray();
        for (FeatureProvider provider : OpenFeatureRecorder.getProviders(domain)) {
            providers.add(provider.getMetadata().getName());
        }
        return new JsonObject()
                .put("state", client.getProviderState().name())
                .put("providers", providers);
    }

    @NonBlocking
    public JsonObject getFlags(String domain) {
        Map<String, FlagValueType> flagMap = new HashMap<>();
        boolean supported = false;
        for (FeatureProvider provider : OpenFeatureRecorder.getProviders(domain)) {
            if (provider instanceof DevFeatureAccess devSpi) {
                supported = true;
                for (DevFeatureAccess.FlagInfo flag : devSpi.getFlags()) {
                    flagMap.putIfAbsent(flag.key(), flag.type());
                }
            }
        }
        if (!supported) {
            return new JsonObject().put("supported", false);
        }
        JsonArray flags = new JsonArray();
        flagMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    JsonObject flag = new JsonObject().put("key", entry.getKey());
                    if (entry.getValue() != null) {
                        flag.put("type", entry.getValue().name().toLowerCase());
                    }
                    flags.add(flag);
                });
        return new JsonObject().put("supported", true).put("flags", flags);
    }

    @NonBlocking
    public JsonObject evaluate(String domain, String key, String type, String defaultValue, String contextJson) {
        Client client = getClient(domain);
        EvaluationContext ctx = parseContext(contextJson);
        try {
            return switch (type) {
                case "boolean" -> {
                    boolean def = Boolean.parseBoolean(defaultValue);
                    yield detailsToJson(client.getBooleanDetails(key, def, ctx));
                }
                case "string" -> {
                    yield detailsToJson(client.getStringDetails(key, defaultValue, ctx));
                }
                case "integer" -> {
                    int def = defaultValue != null && !defaultValue.isEmpty() ? Integer.parseInt(defaultValue) : 0;
                    yield detailsToJson(client.getIntegerDetails(key, def, ctx));
                }
                case "double" -> {
                    double def = defaultValue != null && !defaultValue.isEmpty() ? Double.parseDouble(defaultValue) : 0.0;
                    yield detailsToJson(client.getDoubleDetails(key, def, ctx));
                }
                case "object" -> {
                    yield detailsToJson(client.getObjectDetails(key, new Value(), ctx));
                }
                default -> new JsonObject().put("error", "Unknown type: " + type);
            };
        } catch (Exception e) {
            return new JsonObject().put("error", e.getMessage());
        }
    }

    @NonBlocking
    public JsonObject getEvents(String domain) {
        List<String> names = new ArrayList<>();
        List<List<DevFeatureAccess.EventInfo>> logs = new ArrayList<>();
        for (FeatureProvider provider : OpenFeatureRecorder.getProviders(domain)) {
            if (provider instanceof DevFeatureAccess devSpi) {
                List<DevFeatureAccess.EventInfo> log = devSpi.getEventLog();
                if (!log.isEmpty()) {
                    names.add(provider.getMetadata().getName());
                    logs.add(log);
                }
            }
        }

        JsonArray events = new JsonArray();
        if (logs.size() == 1) {
            String name = names.get(0);
            List<DevFeatureAccess.EventInfo> log = logs.get(0);
            for (int i = log.size() - 1; i >= 0; i--) {
                events.add(eventToJson(log.get(i), name));
            }
        } else if (logs.size() > 1) {
            // merge from the end of each list (all are in chronological order)
            int[] cursors = new int[logs.size()];
            for (int i = 0; i < cursors.length; i++) {
                cursors[i] = logs.get(i).size() - 1;
            }
            for (;;) {
                int best = -1;
                long bestTime = Long.MIN_VALUE;
                for (int i = 0; i < cursors.length; i++) {
                    if (cursors[i] >= 0) {
                        long t = logs.get(i).get(cursors[i]).timestamp();
                        if (t >= bestTime) {
                            bestTime = t;
                            best = i;
                        }
                    }
                }
                if (best < 0) {
                    break;
                }
                events.add(eventToJson(logs.get(best).get(cursors[best]), names.get(best)));
                cursors[best]--;
            }
        }
        return new JsonObject().put("events", events);
    }

    @NonBlocking
    public JsonObject getOverrides(String domain) {
        FlagOverrides overrides = devOverrides.get(domain);
        JsonObject result = new JsonObject();
        if (overrides != null) {
            for (Map.Entry<String, Object> entry : overrides.getAll().entrySet()) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return new JsonObject().put("overrides", result);
    }

    @NonBlocking
    public JsonObject setOverride(String domain, String key, String value, String type) {
        try {
            Object parsed = parseOverrideValue(value, type);
            FlagOverrides current = devOverrides.get(domain);
            FlagOverrides updated = current != null
                    ? current.with(key, parsed)
                    : new FlagOverrides(Map.of(key, parsed));
            devOverrides.put(domain, updated);
            applyOverrides(domain, updated);
            return new JsonObject().put("success", true);
        } catch (Exception e) {
            return new JsonObject().put("error", e.getMessage());
        }
    }

    @NonBlocking
    public JsonObject clearOverride(String domain, String key) {
        FlagOverrides current = devOverrides.get(domain);
        if (current != null) {
            FlagOverrides updated = current.without(key);
            if (updated.isEmpty()) {
                devOverrides.remove(domain);
                clearOverrides(domain);
            } else {
                devOverrides.put(domain, updated);
                applyOverrides(domain, updated);
            }
        }
        return new JsonObject().put("success", true);
    }

    @NonBlocking
    public JsonObject clearAllOverrides(String domain) {
        devOverrides.remove(domain);
        clearOverrides(domain);
        return new JsonObject().put("success", true);
    }

    private void applyOverrides(String domain, FlagOverrides overrides) {
        for (FeatureProvider provider : OpenFeatureRecorder.getProviders(domain)) {
            if (provider instanceof OverrideFeatureAccess access) {
                access.setFlagOverrides(overrides);
            }
        }
    }

    private void clearOverrides(String domain) {
        for (FeatureProvider provider : OpenFeatureRecorder.getProviders(domain)) {
            if (provider instanceof OverrideFeatureAccess access) {
                access.clearFlagOverrides();
            }
        }
    }

    private static Object parseOverrideValue(String value, String type) {
        return switch (type) {
            case "boolean" -> Boolean.parseBoolean(value);
            case "integer" -> Integer.parseInt(value);
            case "double" -> Double.parseDouble(value);
            case "string" -> value;
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }

    private static JsonObject eventToJson(DevFeatureAccess.EventInfo event, String providerName) {
        return new JsonObject()
                .put("timestamp", event.timestamp())
                .put("type", event.type().name())
                .put("message", event.message())
                .put("provider", providerName);
    }

    private Client getClient(String domain) {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        if (OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(domain)) {
            return api.getClient();
        }
        return api.getClient(domain);
    }

    private EvaluationContext parseContext(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return new ImmutableContext();
        }
        JsonObject json = new JsonObject(contextJson);
        Map<String, Value> attributes = new HashMap<>();
        for (String field : json.fieldNames()) {
            Object val = json.getValue(field);
            if (val instanceof Boolean b) {
                attributes.put(field, new Value(b));
            } else if (val instanceof Number n) {
                if (val instanceof Integer || val instanceof Long) {
                    attributes.put(field, new Value(n.intValue()));
                } else {
                    attributes.put(field, new Value(n.doubleValue()));
                }
            } else {
                attributes.put(field, new Value(String.valueOf(val)));
            }
        }
        return new ImmutableContext(attributes);
    }

    private <T> JsonObject detailsToJson(FlagEvaluationDetails<T> details) {
        String valueStr = prettyPrint(details.getValue() instanceof Value v
                ? valueToJson(v)
                : details.getValue());
        JsonObject result = new JsonObject()
                .put("value", valueStr);
        if (details.getVariant() != null) {
            result.put("variant", details.getVariant());
        }
        if (details.getReason() != null) {
            result.put("reason", details.getReason());
        }
        if (details.getErrorCode() != null) {
            result.put("errorCode", details.getErrorCode().name());
        }
        if (details.getErrorMessage() != null) {
            result.put("errorMessage", details.getErrorMessage());
        }
        return result;
    }

    private static String prettyPrint(Object json) {
        if (json instanceof JsonObject obj) {
            return obj.encodePrettily();
        } else if (json instanceof JsonArray arr) {
            return arr.encodePrettily();
        } else {
            return String.valueOf(json);
        }
    }

    private Object valueToJson(Value value) {
        if (value == null || value.isNull()) {
            return null;
        } else if (value.isBoolean()) {
            return value.asBoolean();
        } else if (value.isNumber()) {
            if (value.asInteger() != null && value.asDouble() == value.asInteger().doubleValue()) {
                return value.asInteger();
            }
            return value.asDouble();
        } else if (value.isString()) {
            return value.asString();
        } else if (value.isList()) {
            JsonArray array = new JsonArray();
            for (Value item : value.asList()) {
                array.add(valueToJson(item));
            }
            return array;
        } else if (value.isStructure()) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, Value> entry : value.asStructure().asUnmodifiableMap().entrySet()) {
                obj.put(entry.getKey(), valueToJson(entry.getValue()));
            }
            return obj;
        } else {
            return value.asObject().toString();
        }
    }
}
