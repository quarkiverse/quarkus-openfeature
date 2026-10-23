package io.quarkiverse.openfeature.flagd.test;

import com.google.protobuf.ByteString;

import dev.openfeature.flagd.grpc.sync.Sync.SyncFlagsResponse;
import io.vertx.core.buffer.Buffer;

/**
 * Builds the bytes of a {@code SyncFlags} response for tests that answer the sync call
 * with a bare HTTP/2 server instead of a flagd container.
 */
final class SyncFlagsResponses {
    /**
     * A flag configuration with a single enabled string flag {@code big-flag} and a padding
     * variant of the given size, so that the whole document is at least that large.
     */
    static String flagConfiguration(int paddingSize) {
        return """
                {
                  "flags": {
                    "big-flag": {
                      "state": "ENABLED",
                      "variants": {
                        "on": "hello from a large flag configuration",
                        "padding": "%s"
                      },
                      "defaultVariant": "on"
                    }
                  }
                }
                """.formatted("x".repeat(paddingSize));
    }

    /**
     * One length-prefixed gRPC message carrying the flag configuration.
     */
    static Buffer grpcFrame(String flagConfiguration) {
        ByteString message = SyncFlagsResponse.newBuilder()
                .setFlagConfiguration(flagConfiguration)
                .build()
                .toByteString();
        return Buffer.buffer()
                .appendByte((byte) 0) // not compressed
                .appendInt(message.size())
                .appendBytes(message.toByteArray());
    }
}
