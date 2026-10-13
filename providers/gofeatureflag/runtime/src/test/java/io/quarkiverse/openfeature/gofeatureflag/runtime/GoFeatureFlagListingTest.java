package io.quarkiverse.openfeature.gofeatureflag.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.openfeature.sdk.FlagValueType;
import io.quarkiverse.openfeature.runtime.DevFeatureAccess.FlagInfo;

// The flag definitions below are verbatim excerpts of what the GO Feature Flag relay proxy
// returns from `/v1/flag/configuration` for the seed file used by the provider tests
// (`providers/gofeatureflag/deployment/src/test/resources/flags.goff.yaml`). Note that
// the response carries no declared flag type, which is why the type is inferred.
public class GoFeatureFlagListingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // a plain map, because `FlagInfo.type()` may be `null`
    private static Map<String, FlagValueType> parse(Map<String, String> flags) {
        Map<String, JsonNode> parsed = new LinkedHashMap<>();
        flags.forEach((key, json) -> parsed.put(key, node(json)));
        Map<String, FlagValueType> result = new LinkedHashMap<>();
        for (FlagInfo flag : GoFeatureFlagFeatureProvider.parseFlags(parsed)) {
            result.put(flag.key(), flag.type());
        }
        return result;
    }

    private static JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    private static FlagValueType inferType(String json) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
        return GoFeatureFlagFeatureProvider.inferType(node);
    }

    @Test
    public void typesAreInferredFromTheVariations() {
        assertEquals(FlagValueType.BOOLEAN, inferType("""
                {"variations":{"disabled":false,"enabled":true},"defaultRule":{"variation":"enabled"}}
                """));
        assertEquals(FlagValueType.STRING, inferType("""
                {"variations":{"greeting":"hello"},"defaultRule":{"variation":"greeting"}}
                """));
        assertEquals(FlagValueType.INTEGER, inferType("""
                {"variations":{"ten":10},"defaultRule":{"variation":"ten"}}
                """));
        assertEquals(FlagValueType.DOUBLE, inferType("""
                {"variations":{"pi":3.14},"defaultRule":{"variation":"pi"}}
                """));
        assertEquals(FlagValueType.OBJECT, inferType("""
                {"variations":{"obj":{"key":"value"}},"defaultRule":{"variation":"obj"}}
                """));
        assertEquals(FlagValueType.OBJECT, inferType("""
                {"variations":{"arr":[1,2]},"defaultRule":{"variation":"arr"}}
                """));
    }

    // a value that does not fit in an `int` is reported as a long, even though the provider
    // has no native long path; the SDK default resolves it as a double, which is exact here
    @Test
    public void aValueOutsideTheIntegerRangeIsALong() {
        assertEquals(FlagValueType.LONG, inferType("""
                {"variations":{"big":10000000000,"small":1},"defaultRule":{"variation":"big"}}
                """));
    }

    // GO Feature Flag validates that all variations share a type, but its types are the JSON
    // ones, so a flag may legitimately mix `1` and `3.14`; calling that one an integer would
    // make the Dev UI resolve it as an `int` and show `3`. Numbers are the only variations
    // that need looking at beyond the first one.
    @Test
    public void aNumberFlagIsAsWideAsItsWidestVariation() {
        assertEquals(FlagValueType.DOUBLE, inferType("""
                {"variations":{"one":1,"pi":3.14},"defaultRule":{"variation":"one"}}
                """));
        assertEquals(FlagValueType.LONG, inferType("""
                {"variations":{"one":1,"big":10000000000},"defaultRule":{"variation":"one"}}
                """));
        assertEquals(FlagValueType.DOUBLE, inferType("""
                {"variations":{"big":10000000000,"pi":3.14},"defaultRule":{"variation":"big"}}
                """));
    }

    // GO Feature Flag rejects such a flag, so this cannot arrive from a server; the point is
    // that the provider reports no type rather than inventing one from whichever variation
    // happens to come first
    @Test
    public void disagreeingVariationsHaveUnknownType() {
        assertNull(inferType("""
                {"variations":{"one":1,"greeting":"hello"},"defaultRule":{"variation":"one"}}
                """));
        assertNull(inferType("""
                {"variations":{"greeting":"hello","one":1},"defaultRule":{"variation":"one"}}
                """));
        assertNull(inferType("""
                {"variations":{"enabled":true,"greeting":"hello"},"defaultRule":{"variation":"enabled"}}
                """));
        assertNull(inferType("""
                {"variations":{"obj":{"key":"value"},"one":1},"defaultRule":{"variation":"obj"}}
                """));
    }

    @Test
    public void unknownTypeWhenThereAreNoVariations() {
        assertNull(inferType("""
                {"defaultRule":{"variation":"enabled"}}
                """));
        assertNull(inferType("""
                {"variations":{},"defaultRule":{"variation":"enabled"}}
                """));
        assertNull(inferType("""
                {"variations":{"nothing":null},"defaultRule":{"variation":"nothing"}}
                """));
    }

    @Test
    public void deletedFlagsDisappearFromTheList() {
        String boolFlag = """
                {"variations":{"enabled":true,"disabled":false},"defaultRule":{"variation":"enabled"}}
                """;

        assertEquals(2, parse(Map.of("stays", boolFlag, "goes-away", boolFlag)).size());
        assertEquals(List.of("stays"), List.copyOf(parse(Map.of("stays", boolFlag)).keySet()));
    }

    @Test
    public void malformedOrEmptyFlagsYieldNoTypes() {
        assertEquals(0, GoFeatureFlagFeatureProvider.parseFlags(null).size());
        assertEquals(0, GoFeatureFlagFeatureProvider.parseFlags(Map.of()).size());

        // a flag that is not an object at all is still listed, just without a type
        Map<String, FlagValueType> flags = parse(Map.of("not-a-flag", "\"nonsense\""));
        assertEquals(1, flags.size());
        assertNull(flags.get("not-a-flag"));
    }
}
