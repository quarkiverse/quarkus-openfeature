package io.quarkiverse.openfeature.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

public class SyncClientUrlsTest {
    @Test
    void parseHttpUrl() {
        assertEquals(URI.create("http://flags.example.com:8080/api"),
                SyncClientUrls.parse("Unleash", "http://flags.example.com:8080/api"));
    }

    @Test
    void parseHttpsUrl() {
        assertEquals(URI.create("https://flags.example.com"),
                SyncClientUrls.parse("Unleash", "https://flags.example.com"));
    }

    @Test
    void parseSchemelessUrl() {
        ProviderConfigurationException e = assertThrows(ProviderConfigurationException.class,
                () -> SyncClientUrls.parse("Unleash", "localhost:4242/api"));
        assertTrue(e.getMessage().contains("Unsupported Unleash URL scheme 'localhost'"), e.getMessage());
    }

    @Test
    void parseUrlWithoutSchemeAndPort() {
        ProviderConfigurationException e = assertThrows(ProviderConfigurationException.class,
                () -> SyncClientUrls.parse("Flipt", "flags.example.com"));
        assertTrue(e.getMessage().contains("Unsupported Flipt URL scheme 'null'"), e.getMessage());
    }

    @Test
    void parseUrlWithUnsupportedScheme() {
        ProviderConfigurationException e = assertThrows(ProviderConfigurationException.class,
                () -> SyncClientUrls.parse("Flipt", "grpc://flags.example.com:9000"));
        assertTrue(e.getMessage().contains("Unsupported Flipt URL scheme 'grpc'"), e.getMessage());
    }

    @Test
    void parseUrlWithoutHost() {
        ProviderConfigurationException e = assertThrows(ProviderConfigurationException.class,
                () -> SyncClientUrls.parse("GO Feature Flag", "http:///v1/flag"));
        assertTrue(e.getMessage().contains("The GO Feature Flag URL 'http:///v1/flag' has no host"), e.getMessage());
    }

    @Test
    void parseUrlWithNonNumericPort() {
        ProviderConfigurationException e = assertThrows(ProviderConfigurationException.class,
                () -> SyncClientUrls.parse("GO Feature Flag", "http://flags.example.com:not-a-port"));
        assertTrue(e.getMessage().contains("The authority 'flags.example.com:not-a-port'"), e.getMessage());
        assertTrue(e.getMessage().contains("is not a valid 'host:port'"), e.getMessage());
    }

    @Test
    void parseSyntacticallyInvalidUrl() {
        ProviderConfigurationException e = assertThrows(ProviderConfigurationException.class,
                () -> SyncClientUrls.parse("GO Feature Flag", "http://flags example.com"));
        assertTrue(e.getMessage().contains("Malformed GO Feature Flag URL"), e.getMessage());
    }

    @Test
    void https() {
        assertTrue(SyncClientUrls.isHttps(URI.create("https://flags.example.com")));
    }

    @Test
    void http() {
        assertFalse(SyncClientUrls.isHttps(URI.create("http://flags.example.com")));
    }

    @Test
    void unixSocketIsNotHttps() {
        assertFalse(SyncClientUrls.isHttps(URI.create("unix:///var/run/flags.sock")));
    }

    @Test
    void explicitPort() {
        assertEquals(9000, SyncClientUrls.port(URI.create("https://flags.example.com:9000")));
    }

    @Test
    void explicitPortOfNonHttpUrl() {
        assertEquals(9000, SyncClientUrls.port(URI.create("grpc://flags.example.com:9000")));
    }

    @Test
    void defaultPortOfHttpUrl() {
        assertEquals(80, SyncClientUrls.port(URI.create("http://flags.example.com")));
    }

    @Test
    void defaultPortOfHttpsUrl() {
        assertEquals(443, SyncClientUrls.port(URI.create("https://flags.example.com")));
    }

    @Test
    void portOfNonHttpUrlWithoutPort() {
        assertThrows(IllegalArgumentException.class,
                () -> SyncClientUrls.port(URI.create("unix:///var/run/flags.sock")));
    }

    @Test
    void urlWithoutPath() {
        assertEquals("", SyncClientUrls.basePath(URI.create("https://flags.example.com")));
    }

    @Test
    void urlWithPort() {
        assertEquals("", SyncClientUrls.basePath(URI.create("http://localhost:8080")));
    }

    @Test
    void rootUrl() {
        assertEquals("", SyncClientUrls.basePath(URI.create("https://flags.example.com/")));
    }

    @Test
    void urlWithPrefix() {
        assertEquals("/flags", SyncClientUrls.basePath(URI.create("https://gw.example.com/flags")));
    }

    @Test
    void urlWithTrailingSlash() {
        assertEquals("/flags", SyncClientUrls.basePath(URI.create("https://gw.example.com/flags/")));
    }

    @Test
    void urlWithMultiSegmentPrefix() {
        assertEquals("/gw/flags", SyncClientUrls.basePath(URI.create("https://gw.example.com/gw/flags")));
    }
}
