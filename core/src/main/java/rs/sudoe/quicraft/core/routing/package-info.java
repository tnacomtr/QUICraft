// SPDX-License-Identifier: GPL-3.0-or-later
/**
 * Routing table as data (docs/protocol.md 13.6, 13.7): one UTF-8 JSON resource per Minecraft
 * protocol number, its validator, and an immutable lookup view with digest R.
 *
 * <p>No stream I/O lives here: this package decides which lane a packet belongs to and what its
 * dependency is, and nothing else.
 */
package rs.sudoe.quicraft.core.routing;
