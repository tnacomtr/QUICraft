// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.core.connect.ClientConnector;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.connect.FailureCache;
import rs.sudoe.quicraft.core.discovery.AdvertisementCache;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.QuicraftFabric;

/** Client entrypoint: the transport setting, the advertisement cache and the failure cache. */
public final class QuicraftClient implements ClientModInitializer {
    private static volatile @Nullable QuicraftClient instance;

    private ClientConnector connector;
    private Path configDir;
    private volatile Mode mode = Mode.AUTO;

    /** Null if initialization failed: every hook then takes the vanilla path. */
    static @Nullable QuicraftClient get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        try {
            Hooks.enter("client initialize");
            configDir = QuicraftFabric.configDir();
            mode = ClientSettings.load(configDir);
            connector = new ClientConnector(new AdvertisementCache(),
                    FailureCache.load(configDir.resolve("quic-failures.properties")));
            instance = this;
        } catch (Throwable t) {
            Hooks.failed("client initialize", t);
        }
    }

    ClientConnector connector() {
        return connector;
    }

    Mode mode() {
        return mode;
    }

    void setMode(Mode mode) {
        this.mode = mode;
        ClientSettings.save(configDir, mode);
    }
}
