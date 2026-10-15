// SPDX-License-Identifier: GPL-3.0-or-later
/**
 * Pure, transport-independent channel-binding cryptography and state machine (docs/protocol.md
 * §12.5-12.9). It performs no I/O, never touches Minecraft or Netty classes, never accesses the
 * TLS exporter directly (the transport supplies the 32-byte exporter value through
 * {@link rs.sudoe.quicraft.core.binding.ExporterSource}) and does not perform the cipher cutover
 * described in §12.10-12.13, which belongs to the transport. The state machine accepts exactly
 * OFFER, CLIENT_PROOF, SERVER_PROOF, CLIENT_SWITCH, SERVER_SWITCH, in that order, and returns a
 * typed {@link rs.sudoe.quicraft.core.binding.BindingResult}. It is built through the typed
 * {@link rs.sudoe.quicraft.core.binding.BindingMachineResult}, which reports an exporter failure
 * without an exception.
 */
package rs.sudoe.quicraft.core.binding;