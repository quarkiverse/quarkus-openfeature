package io.quarkiverse.openfeature.unleash.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.openfeature.sdk.FlagValueType;

public class UnleashFlagTypeTest {
    private static FlagValueType typeOf(String variantsJson) {
        String features = """
                {"version": 2, "features": [
                    {"name": "my-flag", "type": "release", "enabled": true, "variants": %s}
                ]}
                """.formatted(variantsJson);
        Map<String, FlagValueType> types = UnleashFeatureProvider.parseFlagTypes(features);
        assertTrue(types.containsKey("my-flag"));
        return types.get("my-flag");
    }

    private static String variant(String name, String payloadType, String payloadValue) {
        return """
                {"name": "%s", "weight": 500, "payload": {"type": "%s", "value": "%s"}}
                """.formatted(name, payloadType, payloadValue);
    }

    @Test
    public void flagWithoutVariantsIsBoolean() {
        assertEquals(FlagValueType.BOOLEAN, typeOf("[]"));
    }

    @Test
    public void missingVariantsFieldIsBoolean() {
        String features = """
                {"version": 2, "features": [
                    {"name": "my-flag", "type": "kill-switch", "enabled": true}
                ]}
                """;
        assertEquals(FlagValueType.BOOLEAN, UnleashFeatureProvider.parseFlagTypes(features).get("my-flag"));
    }

    // The Unleash flag type is a lifecycle classification and must not influence the value type.

    @Test
    public void flagTypeIsIgnored() {
        String features = """
                {"version": 2, "features": [
                    {"name": "released", "type": "release", "enabled": true, "variants": [%s]},
                    {"name": "experimental", "type": "experiment", "enabled": true, "variants": []},
                    {"name": "operational", "type": "operational", "enabled": true, "variants": []},
                    {"name": "permission", "type": "permission", "enabled": true, "variants": []},
                    {"name": "sunset", "type": "sunset", "enabled": true, "variants": []}
                ]}
                """.formatted(variant("a", "string", "hello"));
        Map<String, FlagValueType> types = UnleashFeatureProvider.parseFlagTypes(features);

        // a release flag with string variants is a string flag, not a boolean
        assertEquals(FlagValueType.STRING, types.get("released"));
        // an experiment flag without variants is a boolean flag, not a string
        assertEquals(FlagValueType.BOOLEAN, types.get("experimental"));
        // these three were reported as unknown before, even though they are plain booleans
        assertEquals(FlagValueType.BOOLEAN, types.get("operational"));
        assertEquals(FlagValueType.BOOLEAN, types.get("permission"));
        assertEquals(FlagValueType.BOOLEAN, types.get("sunset"));
    }

    @Test
    public void stringAndCsvPayloadsAreString() {
        assertEquals(FlagValueType.STRING, typeOf("[" + variant("a", "string", "hello") + "]"));
        assertEquals(FlagValueType.STRING, typeOf("[" + variant("a", "csv", "a,b,c") + "]"));
    }

    @Test
    public void jsonPayloadIsObject() {
        assertEquals(FlagValueType.OBJECT, typeOf("[" + variant("a", "json", "{}") + "]"));
    }

    @Test
    public void variantWithoutPayloadIsString() {
        // `extractVariantValue` returns the variant name when there is no payload
        assertEquals(FlagValueType.STRING, typeOf("""
                [{"name": "a", "weight": 1000}]
                """));
    }

    @Test
    public void numberPayloadIsNarrowestTypeThatHoldsIt() {
        assertEquals(FlagValueType.INTEGER, typeOf("[" + variant("a", "number", "42") + "]"));
        assertEquals(FlagValueType.LONG, typeOf("[" + variant("a", "number", "10000000000") + "]"));
        assertEquals(FlagValueType.DOUBLE, typeOf("[" + variant("a", "number", "2.5") + "]"));
    }

    @Test
    public void numberPayloadWidensAcrossVariants() {
        assertEquals(FlagValueType.LONG, typeOf("["
                + variant("a", "number", "42") + ","
                + variant("b", "number", "10000000000") + "]"));
        assertEquals(FlagValueType.DOUBLE, typeOf("["
                + variant("a", "number", "42") + ","
                + variant("b", "number", "2.5") + "]"));
        assertEquals(FlagValueType.DOUBLE, typeOf("["
                + variant("a", "number", "10000000000") + ","
                + variant("b", "number", "2.5") + "]"));
    }

    @Test
    public void unparseableNumberPayloadIsDouble() {
        assertEquals(FlagValueType.DOUBLE, typeOf("[" + variant("a", "number", "not a number") + "]"));
    }

    @Test
    public void agreeingVariantsKeepTheirType() {
        assertEquals(FlagValueType.STRING, typeOf("["
                + variant("a", "string", "x") + ","
                + variant("b", "string", "y") + "]"));
    }

    @Test
    public void disagreeingVariantsHaveUnknownType() {
        assertNull(typeOf("["
                + variant("a", "string", "x") + ","
                + variant("b", "number", "42") + "]"));
        // a variant without a payload evaluates to its name, so it disagrees with a number
        assertNull(typeOf("[" + variant("a", "number", "42") + ", {\"name\": \"b\", \"weight\": 500}]"));
    }

    @Test
    public void unknownPayloadTypeIsUnknown() {
        assertNull(typeOf("[" + variant("a", "xml", "<a/>") + "]"));
    }

    // Variants can be attached to the flag itself or to one of its strategies. A real Unleash
    // server puts everything created through the admin UI in the latter place and leaves the
    // flag-level array empty, so only looking at the flag-level one reports every flag boolean.

    @Test
    public void strategyVariantsDetermineTheType() {
        String features = """
                {"version": 2, "features": [
                    {"name": "my-flag", "type": "release", "enabled": true, "variants": [],
                     "strategies": [{"name": "flexibleRollout", "variants": [%s]}]}
                ]}
                """.formatted(variant("hello", "string", "hello"));
        assertEquals(FlagValueType.STRING, UnleashFeatureProvider.parseFlagTypes(features).get("my-flag"));
    }

    @Test
    public void variantsFromEveryStrategyAreConsidered() {
        String features = """
                {"version": 2, "features": [
                    {"name": "my-flag", "type": "release", "enabled": true,
                     "strategies": [
                        {"name": "default", "variants": []},
                        {"name": "flexibleRollout", "variants": [%s]},
                        {"name": "userWithId", "variants": [%s]}
                     ]}
                ]}
                """.formatted(variant("a", "number", "42"), variant("b", "number", "10000000000"));
        assertEquals(FlagValueType.LONG, UnleashFeatureProvider.parseFlagTypes(features).get("my-flag"));
    }

    @Test
    public void flagAndStrategyVariantsMustAgree() {
        String features = """
                {"version": 2, "features": [
                    {"name": "my-flag", "type": "release", "enabled": true, "variants": [%s],
                     "strategies": [{"name": "flexibleRollout", "variants": [%s]}]}
                ]}
                """.formatted(variant("a", "string", "x"), variant("b", "number", "42"));
        assertNull(UnleashFeatureProvider.parseFlagTypes(features).get("my-flag"));
    }

    @Test
    public void strategiesWithoutVariantsLeaveTheFlagBoolean() {
        String features = """
                {"version": 2, "features": [
                    {"name": "my-flag", "type": "release", "enabled": true, "variants": [],
                     "strategies": [{"name": "default", "constraints": [], "parameters": {}, "variants": []}]}
                ]}
                """;
        assertEquals(FlagValueType.BOOLEAN, UnleashFeatureProvider.parseFlagTypes(features).get("my-flag"));
    }

    @Test
    public void missingFeaturesFieldYieldsNoTypes() {
        assertEquals(Map.of(), UnleashFeatureProvider.parseFlagTypes("{}"));
    }

    @Test
    public void featureWithoutNameIsSkipped() {
        String features = """
                {"version": 2, "features": [
                    {"type": "release", "enabled": true, "variants": []},
                    {"name": "my-flag", "type": "release", "enabled": true, "variants": []}
                ]}
                """;
        assertEquals(Map.of("my-flag", FlagValueType.BOOLEAN), UnleashFeatureProvider.parseFlagTypes(features));
    }
}
