// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric;

import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

/** Common entrypoint (client and dedicated server). */
public final class QuicraftFabric implements ModInitializer {
    public static final org.slf4j.Logger LOG = LoggerFactory.getLogger("QUICraft");

    @Override
    public void onInitialize() {
        try {
            Hooks.enter("initialize");
            routeCoreLogging();
        } catch (Throwable t) {
            Hooks.failed("initialize", t);
        }
    }

    /** config/quicraft/ */
    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("quicraft");
    }

    /** Core logs through java.util.logging; send it to the game's log instead of stderr. */
    private static void routeCoreLogging() {
        Logger core = Logger.getLogger("QUICraft");
        core.setUseParentHandlers(false);
        core.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = record.getMessage();
                Object[] params = record.getParameters();
                if (params != null && message != null) {
                    message = java.text.MessageFormat.format(message, params);
                }
                int level = record.getLevel().intValue();
                if (level >= Level.SEVERE.intValue()) {
                    LOG.error(message, record.getThrown());
                } else if (level >= Level.WARNING.intValue()) {
                    LOG.warn(message, record.getThrown());
                } else if (level >= Level.INFO.intValue()) {
                    LOG.info(message, record.getThrown());
                } else {
                    LOG.debug(message, record.getThrown());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
    }
}
