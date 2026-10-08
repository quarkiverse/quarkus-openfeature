package io.quarkiverse.openfeature.flagd.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.vertx.core.net.SocketAddress;

public class FlagdUrlTest {
    @Test
    void addressWithoutScheme() {
        SocketAddress address = FlagdSyncClient.parseAddress("flagd.example.com:9000");
        assertEquals("flagd.example.com", address.host());
        assertEquals(9000, address.port());
    }

    @Test
    void addressDefaultPort() {
        for (String url : new String[] { "flagd.example.com", "grpc://flagd.example.com" }) {
            assertEquals(8015, FlagdSyncClient.parseAddress(url).port(), url);
        }
    }

    @Test
    void unsupportedSchemeRejected() {
        // `dns`, `xds` and `envoy` are valid gRPC name resolvers that this provider does
        // not implement; `https` and `grpcs` are not gRPC schemes at all
        for (String scheme : new String[] { "https", "http", "grpcs", "dns", "xds", "envoy" }) {
            String url = scheme + "://flagd.example.com:8015";
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> FlagdSyncClient.parseAddress(url), url);
            assertTrue(e.getMessage().contains("Unsupported flagd URL scheme '" + scheme + "'"), e.getMessage());
            assertTrue(e.getMessage().contains("tls-configuration-name"), e.getMessage());
        }
    }

    @Test
    void unixSocketAddress() {
        SocketAddress address = FlagdSyncClient.parseAddress("unix:///var/run/flagd.sock");
        assertTrue(address.isDomainSocket());
        assertEquals("/var/run/flagd.sock", address.path());
    }

    @Test
    void invalidUrl() {
        assertThrows(IllegalArgumentException.class, () -> FlagdSyncClient.parseAddress("grpc:///no-host"));
    }
}
