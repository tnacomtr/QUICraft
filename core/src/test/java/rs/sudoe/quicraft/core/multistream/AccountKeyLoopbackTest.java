// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * docs/protocol.md 13.12 account key: the session's pool and ranking charges carry the account it was started
 * with. The account reaches both server rankings (outbound and inbound) as an exact account entry, and a null
 * account leaves them with none. The 13.12 per-account caps and histories are live only when this holds.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AccountKeyLoopbackTest {

    @Test
    void anAccountReachesBothServerRankingsAsAnExactEntry() throws Exception {
        NRig.Config config = new NRig.Config();
        config.serverAccount = "0f8fad5b-d9cb-469f-a165-70867728950e";
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            assertEquals(1, config.serverResources.inboundRanking().exactAccountCount(), "inbound ranking");
            assertEquals(1, config.serverResources.outboundRanking().exactAccountCount(), "outbound ranking");
        }
    }

    @Test
    void aNullAccountLeavesNoAccountEntry() throws Exception {
        NRig.Config config = new NRig.Config();
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            assertEquals(0, config.serverResources.inboundRanking().exactAccountCount(), "inbound ranking");
            assertEquals(0, config.serverResources.outboundRanking().exactAccountCount(), "outbound ranking");
        }
    }

    @Test
    void anEmptyAccountIsRefusedAtConstruction() throws Exception {
        NRig.Config config = new NRig.Config();
        config.serverAccount = "";
        // The session is built on the loop; a refused construction surfaces as an exception there.
        assertThrows(Exception.class, () -> NRig.start(config).close());
    }
}
