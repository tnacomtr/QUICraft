// SPDX-License-Identifier: GPL-3.0-or-later
/**
 * The client's authenticated pins and its changed-pin re-bind state (docs/protocol.md 12.15, 12.16).
 *
 * <p>A pin records, per normalized logical server, the last endpoint that completed an authenticated
 * login over {@code /3} ({@link rs.sudoe.quicraft.core.pin.PinStore}). It is written only by an
 * explicit call after a valid SERVER_SWITCH; the silent v1 observations of section 6 are never
 * promoted into it. {@link rs.sudoe.quicraft.core.pin.RebindState} holds the per-server exponential
 * backoff that limits a changed-pin re-bind to one automatic attempt per window, plus the
 * at-most-once notice bookkeeping of 12.16 (detailed history and a fixed monotone Bloom filter).
 *
 * <p>Both classes are pure: they do no network I/O, never touch Minecraft or Netty, log no pin
 * contents, and are thread-safe (synchronized methods). They are transport-independent and are not
 * wired into the connect path yet: the caller passes the files it wants under its config directory,
 * reads {@code isUsable()} before trusting a pin, and falls back to TCP whenever it is false. A
 * missing file is an empty, usable state; a corrupt or unreadable one, or a failed write, disables
 * the state for the object's lifetime and is never treated as first contact and never overwritten.
 * {@link rs.sudoe.quicraft.core.pin.RebindState} keeps two files as one unit, so either half
 * present without the other is equally unreliable and fails closed too.
 */
package rs.sudoe.quicraft.core.pin;
