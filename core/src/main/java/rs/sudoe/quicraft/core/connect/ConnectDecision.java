// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.util.Optional;
import rs.sudoe.quicraft.core.discovery.Advertisement;

/** Which transport to use for a connection (docs/protocol.md §4). */
public final class ConnectDecision {
    /** The player's setting. */
    public enum Mode { AUTO, TCP_ONLY, QUIC_ONLY }

    public enum Transport {
        /** Vanilla TCP only. */
        TCP,
        /** QUIC with TCP racing as fallback. */
        RACE,
        /** QUIC without fallback (debugging); failures are shown to the player. */
        QUIC_ONLY
    }

    private ConnectDecision() {}

    /**
     * @param advertisement a fresh advertisement for the server, if any
     * @param failureCacheAllows whether the failure cache lets QUIC be tried for it
     */
    public static Transport decide(Mode mode, boolean quicAvailable, Optional<Advertisement> advertisement,
            boolean failureCacheAllows) {
        switch (mode) {
            case TCP_ONLY:
                return Transport.TCP;
            case QUIC_ONLY:
                return Transport.QUIC_ONLY;
            case AUTO:
            default:
                return quicAvailable && advertisement.isPresent() && failureCacheAllows
                        ? Transport.RACE : Transport.TCP;
        }
    }
}
