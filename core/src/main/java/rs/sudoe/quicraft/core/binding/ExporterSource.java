// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

/**
 * The 32-byte TLS exporter value {@code E} of docs/protocol.md §12.7, supplied by the transport so
 * this package stays free of Netty and Minecraft. The implementation must call the RFC 8446 §7.5
 * normal exporter with label {@code EXPORTER-quicraft-binding-v2}, a supplied empty context
 * (JNI {@code use_context = 1}, length 0) and length 32, on the channel event loop after handshake
 * confirmation, never the early exporter.
 */
public interface ExporterSource {
    /**
     * Returns exactly 32 bytes. Consequences of other lengths are handled by the caller as an
     * exporter error (fatal, docs/protocol.md §12.14).
     */
    byte[] export();
}