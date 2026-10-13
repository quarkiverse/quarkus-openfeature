package io.quarkiverse.openfeature.flipt.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.openfeature.sdk.FlagValueType;
import io.quarkiverse.openfeature.runtime.DevFeatureAccess.FlagInfo;

// The snapshots below follow the shape of `EvaluationNamespaceSnapshot`
// (`rpc/v2/evaluation/evaluationv2.proto` in Flipt), as observed on the wire from
// `flipt/flipt:v2`. Note that a flag carries no list of its variants: a variant is only
// visible as the `variantKey` / `variantAttachment` of a rule distribution, or as the
// optional `defaultVariant`. An absent attachment arrives as the string `"null"`.
public class FliptListingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // a plain map, because `FlagInfo.type()` may be `null`
    private static Map<String, FlagValueType> parse(String json) {
        Map<String, FlagValueType> result = new LinkedHashMap<>();
        for (FlagInfo flag : FliptFeatureProvider.parseFlags(MAPPER, json)) {
            result.put(flag.key(), flag.type());
        }
        return result;
    }

    private static String variantFlag(String key, String variantKey, String attachment) {
        return """
                {
                  "key": "%s",
                  "enabled": true,
                  "type": "VARIANT_FLAG_TYPE",
                  "rules": [{
                    "id": "rule-1",
                    "rank": 1,
                    "distributions": [
                      { "ruleId": "rule-1", "variantKey": "%s", "variantAttachment": %s, "rollout": 100 }
                    ]
                  }],
                  "rollouts": []
                }
                """.formatted(key, variantKey, attachment);
    }

    private static String snapshot(String... flags) {
        return """
                { "namespace": { "key": "default" }, "flags": [%s] }
                """.formatted(String.join(",", flags));
    }

    @Test
    public void onlyAnAttachmentMakesAFlagAnObject() {
        Map<String, FlagValueType> flags = parse(snapshot("""
                {
                  "key": "bool-flag",
                  "enabled": true,
                  "type": "BOOLEAN_FLAG_TYPE",
                  "rules": [],
                  "rollouts": [{ "type": "THRESHOLD_ROLLOUT_TYPE", "rank": 1,
                                 "threshold": { "percentage": 100, "value": true } }]
                }
                """,
                variantFlag("string-flag", "hello", "\"null\""),
                variantFlag("int-flag", "10", "\"null\""),
                variantFlag("long-flag", "10000000000", "\"null\""),
                variantFlag("double-flag", "3.14", "\"null\""),
                variantFlag("ui-config", "default", "\"{\\\"theme\\\":\\\"dark\\\"}\"")));

        assertEquals(6, flags.size());
        assertEquals(FlagValueType.BOOLEAN, flags.get("bool-flag"));
        assertEquals(FlagValueType.STRING, flags.get("string-flag"));
        // a numeric-looking variant key is still a string; see `inferType`
        assertEquals(FlagValueType.STRING, flags.get("int-flag"));
        assertEquals(FlagValueType.STRING, flags.get("long-flag"));
        assertEquals(FlagValueType.STRING, flags.get("double-flag"));
        assertEquals(FlagValueType.OBJECT, flags.get("ui-config"));
    }

    @Test
    public void theDefaultVariantAttachmentCountsTooWhenThereAreNoRules() {
        Map<String, FlagValueType> flags = parse(snapshot("""
                {
                  "key": "int-flag",
                  "enabled": true,
                  "type": "VARIANT_FLAG_TYPE",
                  "rules": [],
                  "rollouts": [],
                  "defaultVariant": { "id": "v1", "key": "10", "attachment": "null" }
                }
                """, """
                {
                  "key": "ui-config",
                  "enabled": true,
                  "type": "VARIANT_FLAG_TYPE",
                  "rules": [],
                  "rollouts": [],
                  "defaultVariant": { "id": "v2", "key": "default", "attachment": "{\\"theme\\":\\"dark\\"}" }
                }
                """));

        assertEquals(FlagValueType.STRING, flags.get("int-flag"));
        assertEquals(FlagValueType.OBJECT, flags.get("ui-config"));
    }

    // Flipt's variants can only disagree about having an attachment, there being no value
    // types to disagree about. An attachment anywhere is enough, because `getObjectEvaluation`
    // is what the Dev UI should offer for such a flag even when another rule leads to a bare
    // variant.
    @Test
    public void variantsThatDisagreeAboutAnAttachmentAreAnObject() {
        Map<String, FlagValueType> flags = parse(snapshot("""
                {
                  "key": "sometimes-an-object",
                  "enabled": true,
                  "type": "VARIANT_FLAG_TYPE",
                  "rules": [{
                    "id": "rule-1",
                    "rank": 1,
                    "distributions": [
                      { "ruleId": "rule-1", "variantKey": "bare", "variantAttachment": "null", "rollout": 50 },
                      { "ruleId": "rule-1", "variantKey": "rich",
                        "variantAttachment": "{\\"theme\\":\\"dark\\"}", "rollout": 50 }
                    ]
                  }],
                  "rollouts": []
                }
                """));

        assertEquals(FlagValueType.OBJECT, flags.get("sometimes-an-object"));
    }

    @Test
    public void aVariantFlagWithNoVariantIsAString() {
        Map<String, FlagValueType> flags = parse(snapshot("""
                {
                  "key": "no-variants",
                  "enabled": true,
                  "type": "VARIANT_FLAG_TYPE",
                  "rules": [],
                  "rollouts": []
                }
                """));

        assertEquals(FlagValueType.STRING, flags.get("no-variants"));
    }

    @Test
    public void unknownTypeWhenTheFlagTypeIsMissing() {
        Map<String, FlagValueType> flags = parse(snapshot("""
                { "key": "no-type", "enabled": true, "rules": [], "rollouts": [] }
                """));

        assertEquals(1, flags.size());
        assertNull(flags.get("no-type"));
    }

    // the latest snapshot is the single source of truth, so that flags deleted on the
    // server disappear from the list instead of lingering forever
    @Test
    public void deletedFlagsDisappearFromTheList() {
        String stays = variantFlag("stays", "hello", "\"null\"");
        String goesAway = variantFlag("goes-away", "hello", "\"null\"");

        assertEquals(2, parse(snapshot(stays, goesAway)).size());
        assertEquals(List.of("stays"), List.copyOf(parse(snapshot(stays)).keySet()));
    }

    @Test
    public void malformedOrEmptySnapshotYieldsNoFlags() {
        assertEquals(0, parse(null).size());
        assertEquals(0, parse("not json at all").size());
        assertEquals(0, parse("{}").size());
        assertEquals(0, parse("{ \"flags\": {} }").size());
    }
}
