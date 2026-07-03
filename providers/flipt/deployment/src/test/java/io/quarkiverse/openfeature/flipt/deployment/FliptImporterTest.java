package io.quarkiverse.openfeature.flipt.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import io.vertx.core.json.JsonObject;

// YAML schema: https://github.com/flipt-io/flipt/blob/v2/core/validation/flipt.cue
// API schema: https://github.com/flipt-io/flipt/blob/v2/rpc/v2/environments/openapi.yaml
public class FliptImporterTest {
    // API requires `@type` on each segment
    @Test
    void segmentsGetType() {
        assertNormalization("""
                segments:
                  - key: everyone
                    name: Everyone
                    match_type: ALL_MATCH_TYPE
                """, """
                {
                  "segments": [{
                    "@type": "flipt.core.Segment",
                    "key": "everyone",
                    "name": "Everyone",
                    "matchType": "ALL_MATCH_TYPE"
                  }]
                }
                """);
    }

    // API requires `@type` on each flag
    @Test
    void flagsGetType() {
        assertNormalization("""
                flags:
                  - key: my-flag
                    name: My Flag
                    type: BOOLEAN_FLAG_TYPE
                    enabled: false
                """, """
                {
                  "flags": [{
                    "@type": "flipt.core.Flag",
                    "key": "my-flag",
                    "name": "My Flag",
                    "type": "BOOLEAN_FLAG_TYPE",
                    "enabled": false
                  }]
                }
                """);
    }

    // YAML omits rollout type; API requires it, inferred from the presence of `threshold`
    @Test
    void thresholdRolloutGetsType() {
        assertNormalization("""
                flags:
                  - key: my-flag
                    name: My Flag
                    type: BOOLEAN_FLAG_TYPE
                    enabled: true
                    rollouts:
                      - threshold:
                          percentage: 100
                          value: true
                """, """
                {
                  "flags": [{
                    "@type": "flipt.core.Flag",
                    "key": "my-flag",
                    "name": "My Flag",
                    "type": "BOOLEAN_FLAG_TYPE",
                    "enabled": true,
                    "rollouts": [{
                      "type": "THRESHOLD_ROLLOUT_TYPE",
                      "threshold": {
                        "percentage": 100,
                        "value": true
                      }
                    }]
                  }]
                }
                """);
    }

    // YAML omits rollout type; API requires it, inferred from the presence of `segment`;
    // also, YAML uses singular `key` while API uses `segments` array
    @Test
    void segmentRolloutGetsType() {
        assertNormalization("""
                flags:
                  - key: my-flag
                    name: My Flag
                    type: BOOLEAN_FLAG_TYPE
                    enabled: true
                    rollouts:
                      - segment:
                          key: beta-users
                          value: true
                """, """
                {
                  "flags": [{
                    "@type": "flipt.core.Flag",
                    "key": "my-flag",
                    "name": "My Flag",
                    "type": "BOOLEAN_FLAG_TYPE",
                    "enabled": true,
                    "rollouts": [{
                      "type": "SEGMENT_ROLLOUT_TYPE",
                      "segment": {
                        "segments": ["beta-users"],
                        "value": true
                      }
                    }]
                  }]
                }
                """);
    }

    // YAML uses `keys` and `operator`; API uses `segments` and `segmentOperator`
    @Test
    void segmentRolloutWithKeysAndOperator() {
        assertNormalization("""
                flags:
                  - key: my-flag
                    name: My Flag
                    type: BOOLEAN_FLAG_TYPE
                    enabled: true
                    rollouts:
                      - segment:
                          keys:
                            - beta-users
                            - internal-users
                          operator: OR_SEGMENT_OPERATOR
                          value: false
                """, """
                {
                  "flags": [{
                    "@type": "flipt.core.Flag",
                    "key": "my-flag",
                    "name": "My Flag",
                    "type": "BOOLEAN_FLAG_TYPE",
                    "enabled": true,
                    "rollouts": [{
                      "type": "SEGMENT_ROLLOUT_TYPE",
                      "segment": {
                        "segments": ["beta-users", "internal-users"],
                        "segmentOperator": "OR_SEGMENT_OPERATOR",
                        "value": false
                      }
                    }]
                  }]
                }
                """);
    }

    // when the YAML already has an explicit rollout type, don't overwrite it
    @Test
    void explicitRolloutTypeIsPreserved() {
        assertNormalization("""
                flags:
                  - key: my-flag
                    name: My Flag
                    type: BOOLEAN_FLAG_TYPE
                    enabled: true
                    rollouts:
                      - type: THRESHOLD_ROLLOUT_TYPE
                        threshold:
                          percentage: 50
                          value: true
                """, """
                {
                  "flags": [{
                    "@type": "flipt.core.Flag",
                    "key": "my-flag",
                    "name": "My Flag",
                    "type": "BOOLEAN_FLAG_TYPE",
                    "enabled": true,
                    "rollouts": [{
                      "type": "THRESHOLD_ROLLOUT_TYPE",
                      "threshold": {
                        "percentage": 50,
                        "value": true
                      }
                    }]
                  }]
                }
                """);
    }

    // YAML marks a variant with `default: true`; API uses `defaultVariant` on the flag
    @Test
    void defaultVariantIsSetFromVariantDefault() {
        assertNormalization("""
                flags:
                  - key: my-flag
                    name: My Flag
                    type: VARIANT_FLAG_TYPE
                    enabled: true
                    variants:
                      - key: "on"
                      - key: "off"
                        default: true
                """, """
                {
                  "flags": [{
                    "@type": "flipt.core.Flag",
                    "key": "my-flag",
                    "name": "My Flag",
                    "type": "VARIANT_FLAG_TYPE",
                    "enabled": true,
                    "defaultVariant": "off",
                    "variants": [
                      {"key": "on"},
                      {"key": "off"}
                    ]
                  }]
                }
                """);
    }

    // no variant has `default: true`, so no `defaultVariant` on the flag
    @Test
    void noDefaultVariantWhenNoVariantIsDefault() {
        assertNormalization("""
                flags:
                  - key: my-flag
                    name: My Flag
                    type: VARIANT_FLAG_TYPE
                    enabled: true
                    variants:
                      - key: "on"
                      - key: "off"
                """, """
                {
                  "flags": [{
                    "@type": "flipt.core.Flag",
                    "key": "my-flag",
                    "name": "My Flag",
                    "type": "VARIANT_FLAG_TYPE",
                    "enabled": true,
                    "variants": [
                      {"key": "on"},
                      {"key": "off"}
                    ]
                  }]
                }
                """);
    }

    // YAML uses singular `segment` (string); API uses `segments` (array)
    @Test
    void singularSegmentRuleIsConvertedToList() {
        assertNormalization("""
                flags:
                  - key: my-flag
                    name: My Flag
                    type: VARIANT_FLAG_TYPE
                    enabled: true
                    variants:
                      - key: "on"
                    rules:
                      - segment: everyone
                        distributions:
                          - variant: "on"
                            rollout: 100
                """, """
                {
                  "flags": [{
                    "@type": "flipt.core.Flag",
                    "key": "my-flag",
                    "name": "My Flag",
                    "type": "VARIANT_FLAG_TYPE",
                    "enabled": true,
                    "variants": [{"key": "on"}],
                    "rules": [{
                      "segments": ["everyone"],
                      "distributions": [{"variant": "on", "rollout": 100}]
                    }]
                  }]
                }
                """);
    }

    // YAML uses `segment` object with `keys` and `operator`; API uses `segments` and `segmentOperator`
    @Test
    void segmentObjectRuleIsConverted() {
        assertNormalization("""
                flags:
                  - key: my-flag
                    name: My Flag
                    type: VARIANT_FLAG_TYPE
                    enabled: true
                    variants:
                      - key: "on"
                    rules:
                      - segment:
                          keys:
                            - beta-users
                            - internal-users
                          operator: AND_SEGMENT_OPERATOR
                        distributions:
                          - variant: "on"
                            rollout: 100
                """, """
                {
                  "flags": [{
                    "@type": "flipt.core.Flag",
                    "key": "my-flag",
                    "name": "My Flag",
                    "type": "VARIANT_FLAG_TYPE",
                    "enabled": true,
                    "variants": [{"key": "on"}],
                    "rules": [{
                      "segments": ["beta-users", "internal-users"],
                      "segmentOperator": "AND_SEGMENT_OPERATOR",
                      "distributions": [{"variant": "on", "rollout": 100}]
                    }]
                  }]
                }
                """);
    }

    // YAML uses snake_case `match_type`; API uses camelCase `matchType`
    // (Protobuf v3 JSON accepts both, but we normalize to the canonical form)
    @Test
    void matchTypeIsRenamed() {
        assertNormalization("""
                segments:
                  - key: beta-users
                    name: Beta Users
                    match_type: ANY_MATCH_TYPE
                    constraints:
                      - type: STRING_COMPARISON_TYPE
                        property: userType
                        operator: eq
                        value: beta
                """, """
                {
                  "segments": [{
                    "@type": "flipt.core.Segment",
                    "key": "beta-users",
                    "name": "Beta Users",
                    "matchType": "ANY_MATCH_TYPE",
                    "constraints": [{
                      "type": "STRING_COMPARISON_TYPE",
                      "property": "userType",
                      "operator": "eq",
                      "value": "beta"
                    }]
                  }]
                }
                """);
    }

    private static void assertNormalization(String yaml, String expectedJson) {
        Map<String, Object> features = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        FliptImporter.normalize(features);
        assertThat(JsonObject.mapFrom(features)).isEqualTo(new JsonObject(expectedJson));
    }
}
