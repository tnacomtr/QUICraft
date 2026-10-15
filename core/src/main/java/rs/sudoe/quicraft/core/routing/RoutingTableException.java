// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

/**
 * A routing resource or a routing entry is invalid (docs/protocol.md 13.6, 13.7).
 *
 * <p>Unchecked on purpose: 13.6 says an unusable table disables {@code /3} before connecting, so a
 * caller cannot accidentally continue with a partially validated table. The message names the rule
 * the input broke.
 */
public class RoutingTableException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public RoutingTableException(String message) {
        super(message);
    }

    public RoutingTableException(String message, Throwable cause) {
        super(message, cause);
    }
}
