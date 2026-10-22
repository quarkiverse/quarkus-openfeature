package io.quarkiverse.openfeature.runtime;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * URL handling shared by the HTTP-based sync clients.
 */
public final class SyncClientUrls {
    /**
     * Parses the configured server URL of the given provider and verifies that it is an HTTP(S)
     * URL with a host. Must be called while the provider is created, so that a misconfiguration
     * fails the application startup instead of throwing on the Vert.x event loop later, where
     * nothing would handle it.
     */
    public static URI parse(String provider, String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ProviderConfigurationException("Malformed " + provider + " URL '" + url + "': " + e.getReason());
        }

        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ProviderConfigurationException("Unsupported " + provider + " URL scheme '" + scheme + "' in '" + url
                    + "'; use 'http://host:port' or 'https://host:port'");
        }
        if (uri.getHost() == null) {
            // a non-numeric port makes the whole authority registry-based, so the host is
            // null even though the URL plainly has one; say what is actually wrong
            if (uri.getAuthority() != null) {
                throw new ProviderConfigurationException("The authority '" + uri.getAuthority() + "' of the " + provider
                        + " URL '" + url + "' is not a valid 'host:port'");
            }
            throw new ProviderConfigurationException("The " + provider + " URL '" + url + "' has no host;"
                    + " use 'http://host:port' or 'https://host:port'");
        }
        return uri;
    }

    /**
     * Whether the configured server URL requires TLS.
     */
    public static boolean isHttps(URI uri) {
        return "https".equals(uri.getScheme());
    }

    /**
     * Returns the port to connect to. When the configured server URL does not specify one,
     * it is the default port of the scheme: 443 for {@code https} and 80 for {@code http}.
     * If the URL doesn't specify a port and has another scheme, an exception is thrown.
     */
    public static int port(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        if (isHttps(uri)) {
            return 443;
        }
        if ("http".equals(uri.getScheme())) {
            return 80;
        }
        throw new IllegalArgumentException("Not an HTTP(S) URL: " + uri);
    }

    /**
     * Returns the path prefix of the configured server URL, which all request paths are
     * relative to. The result is either empty, or starts with a {@code /} and does not end
     * with one, so that a request path can simply be appended to it.
     */
    public static String basePath(URI uri) {
        String path = uri.getPath();
        if (path == null || path.equals("/")) {
            return "";
        }
        if (path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
