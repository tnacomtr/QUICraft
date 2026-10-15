// SPDX-License-Identifier: GPL-3.0-or-later
/**
 * Phase 5 multi-stream (docs/protocol.md §13). The byte-level parts, the version 2 stream
 * preamble (13.2), the auxiliary stream preamble (13.3) and the record format (13.4), have no
 * networking and no routing table. The ordering core ({@link
 * rs.sudoe.quicraft.core.multistream.SenderSequencer}, {@link
 * rs.sudoe.quicraft.core.multistream.ReceiverScheduler}) is transport independent as well.
 * {@link rs.sudoe.quicraft.core.multistream.LaneSession} runs the three QUIC streams of 13.3 over
 * Netty QUIC and connects them to those parts and to the game through {@link
 * rs.sudoe.quicraft.core.multistream.LaneGame}, which has no Minecraft classes. It compiles to
 * Java 8 bytecode. Every failure carries the QUIC application close code the spec names for it.
 */
package rs.sudoe.quicraft.core.multistream;
