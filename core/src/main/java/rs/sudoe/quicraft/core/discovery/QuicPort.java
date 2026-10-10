// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.discovery;

/** Which UDP port a server binds QUIC to (docs/protocol.md §2). */
public final class QuicPort {
    private QuicPort() {}

    /**
     * The configured port, else the game port, unless the query listener is on that UDP port;
     * then the alternative port (default game port + 1, or game port − 1 at the top of the range).
     *
     * @param configured the server owner's QUIC port, 0 for "same as the game port"
     * @param alternative the port to use on a query collision, 0 for the default
     */
    public static int choose(int configured, int alternative, int gamePort, boolean queryEnabled, int queryPort) {
        int wanted = configured != 0 ? configured : gamePort;
        if (queryEnabled && queryPort == wanted) {
            int other = alternative != 0 ? alternative : gamePort + 1;
            return other <= 65535 ? other : gamePort - 1;
        }
        return wanted;
    }
}
