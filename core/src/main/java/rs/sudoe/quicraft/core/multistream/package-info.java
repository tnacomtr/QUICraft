// SPDX-License-Identifier: GPL-3.0-or-later
/**
 * Pure wire codecs for Phase 5 multi-stream (docs/protocol.md §13): the version 2 stream
 * preamble (13.2), the auxiliary stream preamble (13.3) and the record format (13.4). This
 * package is byte-level only: it has no networking, no Netty channels and no routing table,
 * and it compiles to Java 8 bytecode. Every failure carries the QUIC application close code
 * the spec names for it.
 */
package rs.sudoe.quicraft.core.multistream;
