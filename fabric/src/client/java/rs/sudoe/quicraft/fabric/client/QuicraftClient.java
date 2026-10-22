// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.nio.file.Path;
import java.time.Clock;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.SharedConstants;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.connect.ClientConnector;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.connect.FailureCache;
import rs.sudoe.quicraft.core.discovery.AdvertisementCache;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.QuicraftFabric;

/**
 * Client entrypoint: the transport setting, the multistream setting, the advertisement cache and the
 * failure cache. The multistream parts (pin files, their worker thread, a second connector that can
 * select {@code quicraft/3}) exist only once the player turns multistream on.
 */
public final class QuicraftClient implements ClientModInitializer {
    private static volatile @Nullable QuicraftClient instance;

    private ClientConnector connector;
    private AdvertisementCache advertisements;
    private FailureCache failures;
    private ClientConnector multistreamConnector;
    private ClientV3State v3State;
    private Path configDir;
    private volatile Mode mode = Mode.AUTO;
    private volatile boolean multistream;

    /** Null if initialization failed: every hook then takes the vanilla path. */
    public static @Nullable QuicraftClient get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        try {
            Hooks.enter("client initialize");
            configDir = QuicraftFabric.configDir();
            ClientSettings.Loaded settings = ClientSettings.load(configDir);
            mode = settings.mode();
            multistream = settings.multistream();
            advertisements = new AdvertisementCache();
            failures = FailureCache.load(configDir.resolve("quic-failures.properties"));
            connector = new ClientConnector(advertisements, failures);
            instance = this;
            if (mode != Mode.TCP_ONLY && !"false".equals(System.getProperty("quicraft.warmup"))) {
                // -Dquicraft.warmup=false switches it off. One-time QUIC initialisation (natives, event loop, TLS stack) off the first join.
                Thread warm = new Thread(() -> {
                    try {
                        QuicClient.warmUp();
                    } catch (Throwable ignored) {
                        // warming only
                    }
                }, "quicraft-warmup");
                warm.setDaemon(true);
                warm.start();
            }
            if (multistream) {
                multistreamConnector(); // opens the pin files and runs the startup expiry (12.15)
            }
        } catch (Throwable t) {
            Hooks.failed("client initialize", t);
        }
    }

    public ClientConnector connector() {
        return connector;
    }

    /**
     * The connector that can select {@code quicraft/3}, created on first use (so a player who never
     * turns multistream on never has a pin file read or a thread started), or null if it cannot be
     * created: the join then takes the ordinary path. Shares both caches with {@link #connector()}.
     */
    public synchronized ClientConnector multistreamConnector() {
        try {
            Hooks.enter("multistream state");
            if (multistreamConnector != null) {
                return multistreamConnector;
            }
            ClientV3State state = ClientV3State.open(configDir, Clock.systemUTC());
            ClientConnector.V3Support support = new ClientConnector.V3Support(state.pins(), state.rebinds(),
                    state.worker(), System::currentTimeMillis,
                    SharedConstants.getCurrentVersion().protocolVersion(), true,
                    QuicSupport.hasKeyingMaterialExport());
            v3State = state;
            multistreamConnector = new ClientConnector(advertisements, failures, support);
        } catch (Throwable t) {
            Hooks.failed("multistream state", t);
            return null;
        }
        return multistreamConnector;
    }

    /** The multistream state files and their worker, or null before {@link #multistreamConnector()}. */
    synchronized ClientV3State v3State() {
        return v3State;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        ClientSettings.save(configDir, mode, multistream);
    }

    /** Whether the player allows multi-stream ({@code quicraft/3}); off by default (12.1). */
    public boolean multistream() {
        return multistream;
    }

    public void setMultistream(boolean multistream) {
        this.multistream = multistream;
        ClientSettings.save(configDir, mode, multistream);
    }
}
