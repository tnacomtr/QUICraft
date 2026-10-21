// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.SharedConstants;

/**
 * Real-handler parity between TCP order and every /3-legal order (the security review's release
 * blocker). The implementation reads 26.x-only game classes, so it lives in fabric-26x
 * ({@code ParityRunner}) and is loaded by name; on 1.21.11, or any other protocol, this test does
 * nothing. See docs/platforms/fabric.md.
 */
public class ParityGameTest implements FabricClientGameTest {
    private static final int PROTOCOL = 775;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (SharedConstants.getProtocolVersion() != PROTOCOL) {
            QuicraftFabricTestLog.info("[QUICraft] parity skipped: protocol " + SharedConstants.getProtocolVersion()
                    + " is not " + PROTOCOL);
            return;
        }
        try {
            Class.forName("rs.sudoe.quicraft.fabric.gametest.ParityRunner")
                    .getMethod("run", ClientGameTestContext.class).invoke(null, context);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the parity runner is missing from this module", e);
        }
    }
}
