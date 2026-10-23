package io.quarkiverse.openfeature.flagd.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import dev.openfeature.contrib.tools.flagd.api.Evaluator;
import dev.openfeature.flagd.grpc.sync.Sync.SyncFlagsRequest;
import dev.openfeature.flagd.grpc.sync.Sync.SyncFlagsResponse;
import dev.openfeature.sdk.EvaluationContext;
import io.quarkiverse.openfeature.runtime.ProviderConfigurationException;
import io.quarkiverse.openfeature.runtime.SyncClientState;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfigUtils;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.client.GrpcClientOptions;
import io.vertx.grpc.common.GrpcMessageDecoder;
import io.vertx.grpc.common.GrpcMessageEncoder;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.common.MessageSizeOverflowException;
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.common.ServiceName;

public class FlagdSyncClient {
    private static final Logger log = Logger.getLogger(FlagdSyncClient.class);

    // the gRPC message length is an unsigned 32bit number, so Vert.x accepts no more
    private static final long MAX_ALLOWED_MESSAGE_SIZE = 0xFFFF_FFFFL;

    private static final Set<GrpcStatus> FATAL_STATUSES = EnumSet.of(
            GrpcStatus.PERMISSION_DENIED,
            GrpcStatus.UNAUTHENTICATED);

    private static final ServiceMethod<SyncFlagsResponse, SyncFlagsRequest> SYNC_FLAGS = ServiceMethod.client(
            ServiceName.create("flagd.sync.v1.FlagSyncService"),
            "SyncFlags",
            GrpcMessageEncoder.encoder(),
            GrpcMessageDecoder.decoder(SyncFlagsResponse.parser()));

    private final Vertx vertx;
    private final Context context;
    private final FlagdConfig.ProviderConfig config;
    private final Evaluator evaluator;
    private final TlsConfiguration tlsConfig; // null when TLS not configured
    private final SocketAddress address; // null when reading flags from a file
    private final String offlineFlagData; // null unless reading flags from a file
    private final long maxMessageSize;
    private final SyncClientState state;

    // only accessed from the Vert.x event loop, no synchronization needed
    private GrpcClient grpcClient;

    private volatile String latestFlagConfiguration;

    FlagdSyncClient(Vertx vertx, Context context, FlagdConfig.ProviderConfig config, Evaluator evaluator,
            TlsConfigurationRegistry tlsRegistry, SyncClientState state) {
        this.vertx = vertx;
        this.context = context;
        this.config = config;
        this.evaluator = evaluator;
        this.state = state;

        // Vert.x only rejects the value when the gRPC client is created, which happens
        // on the event loop, where an exception would never reach the caller
        this.maxMessageSize = config.maxConfigurationSize().asLongValue();
        if (maxMessageSize <= 0 || maxMessageSize > MAX_ALLOWED_MESSAGE_SIZE) {
            throw new ProviderConfigurationException("The flagd max-configuration-size of " + maxMessageSize
                    + " bytes is out of range, it must be between 1 and " + MAX_ALLOWED_MESSAGE_SIZE + " bytes");
        }

        if (config.tlsConfigurationName().isPresent()) {
            String tlsName = config.tlsConfigurationName().get();
            this.tlsConfig = tlsRegistry.get(tlsName).orElseThrow(() -> new ProviderConfigurationException(
                    "TLS configuration '" + tlsName + "' not found, configure it with quarkus.tls.\"" + tlsName + "\".*"));
        } else {
            this.tlsConfig = null;
        }

        if (config.offlinePath().isPresent()) {
            this.address = null;
            // offline mode reads the file exactly once and never connects to anything, so
            // a file that is missing or does not parse is not a failure anything could
            // recover from: nothing would read it again for the life of the process
            String path = config.offlinePath().get();
            try {
                this.offlineFlagData = Files.readString(Path.of(path));
            } catch (IOException e) {
                throw new ProviderConfigurationException("Cannot read the flagd offline file '" + path
                        + "': " + e.getMessage());
            }
            try {
                evaluator.setFlagsAndGetChangedKeys(offlineFlagData);
            } catch (Exception e) {
                throw new ProviderConfigurationException("Cannot parse the flagd offline file '" + path
                        + "': " + e.getMessage());
            }
        } else {
            this.offlineFlagData = null;
            this.address = parseAddress(config.url());
            if (address.isDomainSocket() && !vertx.isNativeTransportEnabled()) {
                // Vert.x would reject the connection attempt on the event loop, where it only
                // shows up as a reconnect warning repeating forever
                throw new ProviderConfigurationException("The flagd URL '" + config.url() + "' is a Unix domain socket,"
                        + " which Vert.x can only use with a native transport. Set"
                        + " quarkus.vertx.prefer-native-transport=true"
                        + " and add a dependency on io.netty:netty-transport-native-epoll.");
            }
        }
    }

    void start(Listener listener) {
        if (offlineFlagData != null) {
            // the flags are already in the evaluator, the constructor put them there
            latestFlagConfiguration = offlineFlagData;
            state.setReady();
        } else {
            context.runOnContext(v -> {
                startGrpc(address, listener);
            });
        }
    }

    String getLatestFlagConfiguration() {
        return latestFlagConfiguration;
    }

    private void startGrpc(SocketAddress address, Listener listener) {
        grpcClient = createGrpcClient();
        connectStream(address, listener);
    }

    private GrpcClient createGrpcClient() {
        GrpcClientOptions grpcOptions = new GrpcClientOptions()
                .setMaxMessageSize(maxMessageSize);
        // request timeout is cancelled once response headers arrive, so if the connection
        // delivers the headers but stops delivering the body in the middle, it wouldn't be
        // guarded by the request timeout and the response handler wouldn't be called either;
        // we have to enforce the timeout at the socket
        HttpClientOptions httpOptions = grpcOptions.getTransportOptions()
                .setReadIdleTimeout((int) config.streamDeadline().toMillis())
                .setIdleTimeoutUnit(TimeUnit.MILLISECONDS);

        if (tlsConfig != null) {
            httpOptions.setSsl(true);
            httpOptions.setUseAlpn(true);
            TlsConfigUtils.configure(httpOptions, tlsConfig);
        }

        return GrpcClient.client(vertx, grpcOptions);
    }

    private void connectStream(SocketAddress address, Listener listener) {
        if (state.isShutdown()) {
            return;
        }

        SyncFlagsRequest.Builder requestBuilder = SyncFlagsRequest.newBuilder();
        config.providerId().ifPresent(requestBuilder::setProviderId);
        config.selector().ifPresent(requestBuilder::setSelector);
        SyncFlagsRequest request = requestBuilder.build();

        grpcClient.request(address, SYNC_FLAGS)
                .onSuccess(grpcRequest -> {
                    grpcRequest.response()
                            .onSuccess(response -> {
                                response.handler(syncResponse -> {
                                    String flagData = syncResponse.getFlagConfiguration();
                                    log.debugf("Received flag data from flagd: %d bytes", flagData.length());
                                    latestFlagConfiguration = flagData;
                                    try {
                                        List<String> changedKeys = evaluator.setFlagsAndGetChangedKeys(flagData);
                                        EvaluationContext syncContext = syncResponse.hasSyncContext()
                                                ? ProtobufConvert.toEvaluationContext(syncResponse.getSyncContext())
                                                : null;
                                        state.resetReconnectDelay();
                                        listener.onUpdate(changedKeys, syncContext, state.isError());
                                        state.setReady();
                                    } catch (Exception e) {
                                        log.errorf(e, "Failed to process flag data");
                                        state.setError();
                                        listener.onError("Failed to process flag data: " + e.getMessage());
                                    }
                                });
                                response.endHandler(v -> {
                                    GrpcStatus status = response.status();
                                    if (status != null && FATAL_STATUSES.contains(status)) {
                                        log.errorf("flagd returned fatal gRPC status: %s", status);
                                        state.setShutdown();
                                        listener.onFatalError("flagd returned " + status);
                                        return;
                                    }
                                    log.debug("flagd sync stream completed, will reconnect");
                                    state.setError();
                                    listener.onError("stream completed unexpectedly");
                                    state.scheduleReconnect(() -> connectStream(address, listener));
                                });
                                response.exceptionHandler(t -> {
                                    if (state.isShutdown()) {
                                        return;
                                    }
                                    // Vert.x cancels the call and fails the response on an oversized
                                    // message, so this is the only chance to say what to do about it
                                    String message = t instanceof MessageSizeOverflowException
                                            ? "flag configuration exceeds the configured maximum of " + maxMessageSize
                                                    + " bytes, raise quarkus.openfeature.flagd.max-configuration-size"
                                            : t.getMessage();
                                    log.warnf(t, "flagd sync stream error, will reconnect: %s", message);
                                    state.setError();
                                    listener.onError(message);
                                    state.scheduleReconnect(() -> connectStream(address, listener));
                                });
                            }).onFailure(t -> {
                                log.warnf(t, "Failed to get response from flagd, will reconnect");
                                state.setError();
                                listener.onError(t.getMessage());
                                state.scheduleReconnect(() -> connectStream(address, listener));
                            });

                    grpcRequest.end(request);
                }).onFailure(t -> {
                    log.warnf(t, "Failed to connect to flagd at %s, will reconnect", config.url());
                    state.setError();
                    listener.onError(t.getMessage());
                    state.scheduleReconnect(() -> connectStream(address, listener));
                });
    }

    boolean isShutdown() {
        return state.isShutdown();
    }

    void awaitInitialized() throws Exception {
        state.awaitInitialized();
    }

    // called on the Vert.x event loop
    void shutdown() {
        state.cancelReconnect();
        if (grpcClient != null) {
            grpcClient.close();
        }
    }

    static SocketAddress parseAddress(String url) {
        URI uri;
        try {
            uri = new URI(url.contains("://") ? url : "grpc://" + url);
        } catch (URISyntaxException e) {
            throw new ProviderConfigurationException("Malformed flagd URL '" + url + "': " + e.getReason());
        }

        // the scheme carries no TLS meaning here: flagd sync is gRPC, where a URI scheme
        // selects a name resolver. Rather than ignore an `https://` that the user wrote
        // expecting encryption, refuse everything we don't actually implement
        String scheme = uri.getScheme();
        if (!"grpc".equals(scheme) && !"unix".equals(scheme)) {
            throw new ProviderConfigurationException("Unsupported flagd URL scheme '" + scheme + "' in '" + url
                    + "'; flagd sync is gRPC, use 'host:port' or 'unix:///path'"
                    + " and enable TLS with quarkus.openfeature.flagd.tls-configuration-name");
        }

        if ("unix".equals(scheme)) {
            // the socket path is the URI path, so it must be absolute and the authority empty
            String path = uri.getPath();
            if (uri.getAuthority() != null || path == null || path.isEmpty() || "/".equals(path)) {
                throw new ProviderConfigurationException("Invalid flagd Unix socket URL '" + url
                        + "'; the path must be absolute, as in 'unix:///var/run/flagd.sock'");
            }
            return SocketAddress.domainSocketAddress(path);
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null) {
            throw new ProviderConfigurationException("Invalid flagd URL: " + url);
        }
        return SocketAddress.inetSocketAddress(port > 0 ? port : 8015, host);
    }

    interface Listener {
        void onUpdate(List<String> changedKeys, EvaluationContext syncContext, boolean reconnected);

        void onError(String message);

        void onFatalError(String message);
    }
}
