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
     * Returns the 32-byte exporter value {@code E} of docs/protocol.md §12.7. The implementation
     * keeps ownership of the array it returns: handing out the same instance on every call is
     * safe, and callers must neither modify nor wipe it. {@link BindingStateMachine} derives from a
     * private copy and wipes only that copy. A source that throws, returns null or returns another
     * length is an exporter error (fatal, docs/protocol.md §12.3, §12.14), reported by the machine
     * factories as {@link BindingFailure#EXPORTER_ERROR}.
     *
     * @return exactly 32 raw bytes
     */
    byte[] export();
}