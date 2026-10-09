package io.quarkiverse.openfeature.flagd.runtime;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jboss.logging.Logger;

import dev.openfeature.contrib.tools.flagd.api.Evaluator;
import dev.openfeature.flagd.grpc.sync.Sync.SyncFlagsRequest;
import dev.openfeature.flagd.grpc.sync.Sync.SyncFlagsResponse;
import dev.openfeature.sdk.EvaluationContext;
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
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.common.ServiceName;

public class FlagdSyncClient {
    private static final Logger log = Logger.getLogger(FlagdSyncClient.class);

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
    private final TlsConfigurationRegistry tlsRegistry;
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
        this.tlsRegistry = tlsRegistry;
        this.state = state;
    }

    void start(Listener listener) throws Exception {
        if (config.offlinePath().isPresent()) {
            startOffline(config.offlinePath().get());
        } else {
            SocketAddress address = parseAddress(config.url());
            if (address.isDomainSocket() && !vertx.isNativeTransportEnabled()) {
                // Vert.x would reject the connection attempt on the event loop, where it only
                // shows up as a reconnect warning repeating forever
                throw new IllegalStateException("The flagd URL '" + config.url() + "' is a Unix domain socket,"
                        + " which Vert.x can only use with a native transport. Set"
                        + " quarkus.vertx.prefer-native-transport=true"
                        + " and add a dependency on io.netty:netty-transport-native-epoll.");
            }
            context.runOnContext(v -> {
                startGrpc(address, listener);
            });
        }
    }

    String getLatestFlagConfiguration() {
        return latestFlagConfiguration;
    }

    private void startOffline(String path) throws Exception {
        String flagData = Files.readString(Path.of(path));
        latestFlagConfiguration = flagData;
        evaluator.setFlagsAndGetChangedKeys(flagData);
        state.setReady();
    }

    private void startGrpc(SocketAddress address, Listener listener) {
        grpcClient = createGrpcClient();
        connectStream(address, listener);
    }

    private GrpcClient createGrpcClient() {
        GrpcClientOptions grpcOptions = new GrpcClientOptions();
        HttpClientOptions httpOptions = grpcOptions.getTransportOptions();

        if (config.tlsConfigurationName().isPresent()) {
            String tlsName = config.tlsConfigurationName().get();
            Optional<TlsConfiguration> tlsConfig = tlsRegistry.get(tlsName);
            if (tlsConfig.isEmpty()) {
                throw new IllegalStateException("TLS configuration not found: " + tlsName);
            }
            httpOptions.setSsl(true);
            httpOptions.setUseAlpn(true);
            TlsConfigUtils.configure(httpOptions, tlsConfig.get());
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
                    grpcRequest.idleTimeout(config.streamDeadline().toMillis());
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
                                    log.warnf(t, "flagd sync stream error, will reconnect");
                                    state.setError();
                                    listener.onError(t.getMessage());
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
        URI uri = URI.create(url.contains("://") ? url : "grpc://" + url);

        // the scheme carries no TLS meaning here: flagd sync is gRPC, where a URI scheme
        // selects a name resolver. Rather than ignore an `https://` that the user wrote
        // expecting encryption, refuse everything we don't actually implement
        String scheme = uri.getScheme();
        if (!"grpc".equals(scheme) && !"unix".equals(scheme)) {
            throw new IllegalArgumentException("Unsupported flagd URL scheme '" + scheme + "' in '" + url
                    + "'; flagd sync is gRPC, use 'host:port' or 'unix:///path'"
                    + " and enable TLS with quarkus.openfeature.flagd.tls-configuration-name");
        }

        if ("unix".equals(scheme)) {
            return SocketAddress.domainSocketAddress(uri.getPath());
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null) {
            throw new IllegalArgumentException("Invalid flagd URL: " + url);
        }
        return SocketAddress.inetSocketAddress(port > 0 ? port : 8015, host);
    }

    interface Listener {
        void onUpdate(List<String> changedKeys, EvaluationContext syncContext, boolean reconnected);

        void onError(String message);

        void onFatalError(String message);
    }
}
