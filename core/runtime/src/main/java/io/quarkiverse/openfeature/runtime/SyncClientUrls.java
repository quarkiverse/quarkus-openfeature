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
     * it is 443 for {@code https} and {@code defaultPlainPort} otherwise.
     */
    public static int port(URI uri, int defaultPlainPort) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return isHttps(uri) ? 443 : defaultPlainPort;
    }

    /**
     * Returns the path prefix of the configured server URL, which all request paths are
     * relative to. The result is either empty, or starts with a {@code /} and does not end
     * with one, so that a request path can simply be appended to it.
     * <p>
     * A prefix is typical when the flag server sits behind a gateway.
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
