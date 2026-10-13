package io.quarkiverse.openfeature.runtime;

import java.net.URI;

/**
 * URL handling shared by the HTTP-based sync clients.
 */
public final class SyncClientUrls {
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
