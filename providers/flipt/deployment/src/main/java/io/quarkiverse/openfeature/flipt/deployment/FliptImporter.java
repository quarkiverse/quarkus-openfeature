package io.quarkiverse.openfeature.flipt.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

public class FliptImporter {
    public static void run(String baseUrl, InputStream featuresYaml) {
        run(baseUrl, featuresYaml, null, null);
    }

    @SuppressWarnings("unchecked")
    public static void run(String baseUrl, InputStream featuresYaml,
            String authToken, WebClientOptions options) {
        Map<String, Object> features;
        try (featuresYaml) {
            features = new Yaml(new SafeConstructor(new LoaderOptions())).load(featuresYaml);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String namespace = (String) features.getOrDefault("namespace", "default");
        String resourcesUrl = baseUrl + "/api/v2/environments/default/namespaces/"
                + URLEncoder.encode(namespace, StandardCharsets.UTF_8) + "/resources";

        Vertx vertx = Vertx.vertx();
        try {
            WebClient client = options != null ? WebClient.create(vertx, options) : WebClient.create(vertx);
            try {
                List<Map<String, Object>> segments = (List<Map<String, Object>>) features.get("segments");
                if (segments != null) {
                    for (Map<String, Object> segment : segments) {
                        segment.put("@type", "flipt.core.Segment");
                        postResource(client, resourcesUrl, (String) segment.get("key"), segment, authToken);
                    }
                }

                List<Map<String, Object>> flags = (List<Map<String, Object>>) features.get("flags");
                if (flags != null) {
                    for (Map<String, Object> flag : flags) {
                        flag.put("@type", "flipt.core.Flag");
                        normalizeFlag(flag);
                        postResource(client, resourcesUrl, (String) flag.get("key"), flag, authToken);
                    }
                }
            } finally {
                client.close();
            }
        } finally {
            vertx.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void normalizeFlag(Map<String, Object> flag) {
        List<Map<String, Object>> rollouts = (List<Map<String, Object>>) flag.get("rollouts");
        if (rollouts != null) {
            for (Map<String, Object> rollout : rollouts) {
                if (!rollout.containsKey("type")) {
                    if (rollout.containsKey("threshold")) {
                        rollout.put("type", "THRESHOLD_ROLLOUT_TYPE");
                    } else if (rollout.containsKey("segment")) {
                        rollout.put("type", "SEGMENT_ROLLOUT_TYPE");
                    }
                }
            }
        }

        List<Map<String, Object>> rules = (List<Map<String, Object>>) flag.get("rules");
        if (rules != null) {
            for (Map<String, Object> rule : rules) {
                if (rule.containsKey("segment") && !rule.containsKey("segments")) {
                    Object segment = rule.remove("segment");
                    List<Object> segments = new ArrayList<>();
                    segments.add(segment);
                    rule.put("segments", segments);
                }
            }
        }
    }

    private static void postResource(WebClient client, String url, String key,
            Map<String, Object> payload, String authToken) {
        JsonObject body = new JsonObject()
                .put("key", key)
                .put("payload", JsonObject.mapFrom(payload));

        var request = client.postAbs(url)
                .putHeader("Content-Type", "application/json");
        if (authToken != null) {
            request.putHeader("Authorization", "Bearer " + authToken);
        }
        var response = Future.await(request.sendJsonObject(body));
        if (response.statusCode() >= 300) {
            throw new RuntimeException("Failed to create resource '" + key + "': HTTP " + response.statusCode()
                    + " " + response.bodyAsString());
        }
    }
}
