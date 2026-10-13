package io.quarkiverse.openfeature.flagd.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.openfeature.sdk.FlagValueType;
import io.quarkiverse.openfeature.runtime.DevFeatureAccess.FlagInfo;

// The flag configurations below follow the flagd provider schema,
// https://flagd.dev/schema/v0/flags.json, and are modelled on its
// `examples/minimal.json`: `flags` is a required object keyed by flag key,
// and every flag requires `state` and `variants`.
//
// The exceptions are `no-variants`, which deliberately omits the required
// `variants`, and the flags of `disagreeingVariantsHaveUnknownType`, whose
// variants do not share one type. Neither is schema-valid, and that is the point
// of those tests: a degenerate configuration must still yield a flag list rather
// than an exception or an invented type.
public class FlagdListingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // a plain map, because `FlagInfo.type()` may be `null`
    private static Map<String, FlagValueType> parse(String json) {
        Map<String, FlagValueType> result = new LinkedHashMap<>();
        for (FlagInfo flag : FlagdFeatureProvider.parseFlags(MAPPER, json)) {
            result.put(flag.key(), flag.type());
        }
        return result;
    }

    @Test
    public void typesAreInferredFromTheVariants() {
        Map<String, FlagValueType> flags = parse("""
                {
                  "flags": {
                    "bool-flag": {
                      "state": "ENABLED",
                      "defaultVariant": "on",
                      "variants": { "on": true, "off": false }
                    },
                    "string-flag": {
                      "state": "ENABLED",
                      "defaultVariant": "greeting",
                      "variants": { "greeting": "hello" }
                    },
                    "int-flag": {
                      "state": "ENABLED",
                      "defaultVariant": "one",
                      "variants": { "one": 1 }
                    },
                    "double-flag": {
                      "state": "ENABLED",
                      "defaultVariant": "pi",
                      "variants": { "pi": 3.14 }
                    },
                    "object-flag": {
                      "state": "ENABLED",
                      "defaultVariant": "obj",
                      "variants": { "obj": { "key": "value" } }
                    }
                  }
                }
                """);

        assertEquals(5, flags.size());
        assertEquals(FlagValueType.BOOLEAN, flags.get("bool-flag"));
        assertEquals(FlagValueType.STRING, flags.get("string-flag"));
        assertEquals(FlagValueType.INTEGER, flags.get("int-flag"));
        assertEquals(FlagValueType.DOUBLE, flags.get("double-flag"));
        assertEquals(FlagValueType.OBJECT, flags.get("object-flag"));
    }

    @Test
    public void unknownTypeWhenThereAreNoVariants() {
        Map<String, FlagValueType> flags = parse("""
                {
                  "flags": {
                    "no-variants": { "state": "ENABLED", "defaultVariant": "on" }
                  }
                }
                """);

        assertEquals(1, flags.size());
        assertTrue(flags.containsKey("no-variants"));
        assertEquals(null, flags.get("no-variants"));
    }

    // the variants define the type whether or not `defaultVariant` points at one of them
    @Test
    public void aDanglingDefaultVariantDoesNotHideTheType() {
        Map<String, FlagValueType> flags = parse("""
                {
                  "flags": {
                    "missing-variant": {
                      "state": "ENABLED",
                      "defaultVariant": "nope",
                      "variants": { "on": true }
                    }
                  }
                }
                """);

        assertEquals(FlagValueType.BOOLEAN, flags.get("missing-variant"));
    }

    // the schema makes a flag a boolean, number, string or object flag and requires every
    // variant to match, so variants that disagree describe no type at all
    @Test
    public void disagreeingVariantsHaveUnknownType() {
        Map<String, FlagValueType> flags = parse("""
                {
                  "flags": {
                    "bool-and-string": {
                      "state": "ENABLED",
                      "defaultVariant": "on",
                      "variants": { "on": true, "greeting": "hello" }
                    },
                    "number-and-string": {
                      "state": "ENABLED",
                      "defaultVariant": "one",
                      "variants": { "one": 1, "greeting": "hello" }
                    },
                    "object-and-number": {
                      "state": "ENABLED",
                      "defaultVariant": "obj",
                      "variants": { "obj": { "key": "value" }, "one": 1 }
                    }
                  }
                }
                """);

        assertEquals(null, flags.get("bool-and-string"));
        assertEquals(null, flags.get("number-and-string"));
        assertEquals(null, flags.get("object-and-number"));
    }

    // the schema says `number`, so `1` and `3.14` may share a flag
    @Test
    public void aNumberFlagIsAsWideAsItsWidestVariant() {
        Map<String, FlagValueType> flags = parse("""
                {
                  "flags": {
                    "int-and-long": {
                      "state": "ENABLED",
                      "defaultVariant": "one",
                      "variants": { "one": 1, "big": 10000000000 }
                    },
                    "int-and-double": {
                      "state": "ENABLED",
                      "defaultVariant": "one",
                      "variants": { "one": 1, "pi": 3.14 }
                    }
                  }
                }
                """);

        assertEquals(FlagValueType.LONG, flags.get("int-and-long"));
        assertEquals(FlagValueType.DOUBLE, flags.get("int-and-double"));
    }

    @Test
    public void deletedFlagsDisappearFromTheList() {
        String before = """
                {
                  "flags": {
                    "stays": {
                      "state": "ENABLED",
                      "defaultVariant": "on",
                      "variants": { "on": true, "off": false }
                    },
                    "goes-away": {
                      "state": "ENABLED",
                      "defaultVariant": "on",
                      "variants": { "on": true, "off": false }
                    }
                  }
                }
                """;
        String after = """
                {
                  "flags": {
                    "stays": {
                      "state": "ENABLED",
                      "defaultVariant": "on",
                      "variants": { "on": true, "off": false }
                    }
                  }
                }
                """;

        assertEquals(2, parse(before).size());
        assertEquals(List.of("stays"), List.copyOf(parse(after).keySet()));
    }

    @Test
    public void malformedOrEmptyConfigurationYieldsNoFlags() {
        assertEquals(0, parse(null).size());
        assertEquals(0, parse("not json at all").size());
        // `flags` is required by the schema
        assertEquals(0, parse("{}").size());
        // the array form of `flags` is `flagdConfig`, which only the flagd daemon
        // accepts; a provider receives the object form
        assertEquals(0, parse("{ \"flags\": [] }").size());
    }
}
