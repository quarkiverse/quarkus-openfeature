package io.quarkiverse.openfeature.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

public class SyncClientUrlsTest {
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
        assertEquals(9000, SyncClientUrls.port(URI.create("https://flags.example.com:9000"), 8080));
    }

    @Test
    void defaultPortOfHttpUrl() {
        assertEquals(8080, SyncClientUrls.port(URI.create("http://flags.example.com"), 8080));
    }

    @Test
    void defaultPortOfHttpsUrl() {
        assertEquals(443, SyncClientUrls.port(URI.create("https://flags.example.com"), 8080));
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
