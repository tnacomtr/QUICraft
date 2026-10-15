// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.handler.codec.quic.QuicChannel;
import rs.sudoe.quicraft.core.QuicSupport;

/**
 * The TLS exporter of docs/protocol.md §12.7: bytes that both ends of a QUIC connection derive
 * from its TLS session, and that nobody without that session's secrets can compute. Channel
 * binding uses them to tie a Minecraft secret to the connection that carries it (§12).
 *
 * <p>Only QUICraft's patched Netty QUIC build has the binding
 * (natives/patches/netty/0004-keying-material-export.patch). Where the classes run with upstream
 * Netty's native, {@link QuicSupport#hasKeyingMaterialExport()} is false and core must not select
 * anything that needs the exporter (§12.3).
 */
public final class TlsExporter {
    /** The §12.7 binding label: US-ASCII, without a terminating NUL. */
    public static final String BINDING_LABEL = "EXPORTER-quicraft-binding-v2";

    /** The §12.7 binding output length in bytes. */
    public static final int BINDING_LENGTH = 32;

    /** §12.7's context: the empty byte string, supplied explicitly on the exporter call. */
    private static final byte[] NO_CONTEXT = new byte[0];

    private TlsExporter() {}

    /**
     * The §12.7 exporter value {@code E} of the connection a stream belongs to: 32 bytes for
     * {@link #BINDING_LABEL} with the empty context.
     *
     * <p>Must run on the stream's own thread: see {@link QuicByteStream#inEventLoop()}. Returns
     * {@code null} before the handshake has completed, once the connection is closed, or if the
     * native export failed. A caller must treat {@code null} as an error and never as an empty
     * value: after a peer selected the multi-stream ALPN, an exporter error is fatal (§12.3), not
     * permission to continue without binding.
     */
    public static byte[] bindingValue(QuicByteStream stream) {
        return export(stream, BINDING_LABEL, NO_CONTEXT, BINDING_LENGTH);
    }

    /**
     * As {@link #bindingValue}, for another label, context or length. The label must be US-ASCII;
     * the context may be {@code null} or empty, and the two are the same exporter call.
     */
    public static byte[] export(QuicByteStream stream, String label, byte[] context, int length) {
        if (stream == null) {
            throw new NullPointerException("stream");
        }
        if (!(stream instanceof NettyQuicByteStream)) {
            throw new IllegalArgumentException("not a QUIC connection: " + stream.getClass().getName());
        }
        if (!stream.inEventLoop()) {
            throw new IllegalStateException("the TLS exporter must run on the stream's event loop");
        }
        QuicChannel connection = ((NettyQuicByteStream) stream).connection();
        return connection.exportKeyingMaterial(label, context, length);
    }
}
