package io.quarkiverse.openfeature.gofeatureflag.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class GoFeatureFlagSseUriTest {
    private static final String PATH = "/stream/v1/sse/flag/change";

    @Test
    void noApiKey() {
        assertEquals(PATH, GoFeatureFlagSyncClient.sseUri(null));
    }

    @Test
    void simpleApiKey() {
        assertEquals(PATH + "?apiKey=test-secret", GoFeatureFlagSyncClient.sseUri("test-secret"));
    }

    // an ampersand would otherwise end the parameter and the rest of the key
    // would be read by the server as another query parameter
    @Test
    void apiKeyWithAmpersand() {
        assertEquals(PATH + "?apiKey=a%26b", GoFeatureFlagSyncClient.sseUri("a&b"));
    }

    // a hash would otherwise start the URI fragment and the rest of the key
    // would not be sent to the server at all
    @Test
    void apiKeyWithHash() {
        assertEquals(PATH + "?apiKey=a%23b", GoFeatureFlagSyncClient.sseUri("a#b"));
    }

    // the server decodes a plus in a query parameter as a space, so a key that
    // contains a plus would arrive with that character replaced
    @Test
    void apiKeyWithPlus() {
        assertEquals(PATH + "?apiKey=a%2Bb", GoFeatureFlagSyncClient.sseUri("a+b"));
    }

    // a space is not valid in a request URI at all
    @Test
    void apiKeyWithSpace() {
        assertEquals(PATH + "?apiKey=a+b", GoFeatureFlagSyncClient.sseUri("a b"));
    }

    // a percent sign starts an escape sequence, so the server would either
    // fail to parse the key or decode it into something else
    @Test
    void apiKeyWithPercent() {
        assertEquals(PATH + "?apiKey=a%252Fb", GoFeatureFlagSyncClient.sseUri("a%2Fb"));
    }
}
