// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class QuicraftFabricTestLog {
    private static final Logger LOG = LoggerFactory.getLogger("QUICraft gametest");

    private QuicraftFabricTestLog() {}

    static void info(String message) {
        LOG.info(message);
    }
}
