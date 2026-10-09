// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundHelloPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ping.clientbound.ClientboundPongResponsePacket;

/**
 * Listener for one bench join. Packet callbacks run on the Netty event loop; the bench thread
 * reads the volatile fields and latches.
 *
 * <p>MCProtocolLib's own listener already answers login, configuration and keepalive. This class
 * adds what a vanilla client also does: client settings, chunk-batch acks, teleport confirm and
 * "player loaded".
 */
final class BenchSession extends SessionAdapter {
    /** Chunks per tick reported in chunk-batch acks. Fixed so runs are comparable. */
    private static final float CHUNKS_PER_TICK = 64.0f;

    private final MinecraftProtocol protocol;
    private final int viewDistance;
    private final boolean verbose;
    private final java.util.Set<Class<?>> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    final CountDownLatch joined = new CountDownLatch(1);
    final CountDownLatch positioned = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    final AtomicInteger chunks = new AtomicInteger();
    final ConcurrentLinkedQueue<Long> rttNanos = new ConcurrentLinkedQueue<>();

    volatile long helloNanos;
    volatile long loginFinishedNanos;
    volatile long configurationNanos;
    volatile long loginNanos;
    volatile long lastChunkNanos;
    volatile Vector3d position;
    volatile float yaw;
    volatile float pitch;
    volatile boolean benchClosing;
    volatile boolean disconnected;
    volatile String disconnectReason;

    private final long created = System.nanoTime();
    private volatile boolean loadedSent;

    BenchSession(MinecraftProtocol protocol, int viewDistance, boolean verbose) {
        this.protocol = protocol;
        this.viewDistance = viewDistance;
        this.verbose = verbose;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        long now = System.nanoTime();
        if (verbose && seen.add(packet.getClass())) {
            System.out.printf("  +%.1fms %s %s%n", (now - created) / 1e6, protocol.getInboundState(),
                    packet.getClass().getSimpleName());
        }
        if (configurationNanos == 0 && protocol.getInboundState() == ProtocolState.CONFIGURATION) {
            configurationNanos = now;
        }
        switch (packet) {
            case ClientboundHelloPacket p -> helloNanos = now;
            case ClientboundLoginFinishedPacket p -> {
                loginFinishedNanos = now;
                if (helloNanos == 0) {
                    helloNanos = now; // offline mode: no encryption request
                }
                // This listener runs before MCProtocolLib's, which then acknowledges and
                // switches the outbound state; queue the send behind that.
                session.getChannel().eventLoop().execute(() -> sendClientInformation(session));
            }
            case ClientboundLoginPacket p -> {
                loginNanos = now;
                joined.countDown();
            }
            case ClientboundLevelChunkWithLightPacket p -> {
                lastChunkNanos = now;
                chunks.incrementAndGet();
            }
            case ClientboundChunkBatchFinishedPacket p ->
                    session.send(new ServerboundChunkBatchReceivedPacket(CHUNKS_PER_TICK));
            case ClientboundPlayerPositionPacket p -> onPosition(session, p);
            case ClientboundPongResponsePacket p -> rttNanos.add(now - p.getPingTime());
            default -> {}
        }
    }

    /**
     * Vanilla sends its settings right after Login Acknowledged; without them the backend
     * assumes a view distance of 2.
     */
    private void sendClientInformation(Session session) {
        if (protocol.getOutboundState() != ProtocolState.CONFIGURATION) {
            throw new IllegalStateException("expected configuration state, got " + protocol.getOutboundState());
        }
        if (verbose) {
            System.out.printf("  +%.1fms sending client information (view distance %d)%n",
                    (System.nanoTime() - created) / 1e6, viewDistance);
        }
        session.send(new ServerboundClientInformationPacket(
                "en_us", viewDistance, ChatVisibility.FULL, true, List.of(SkinPart.values()),
                HandPreference.RIGHT_HAND, false, true, ParticleStatus.ALL));
    }

    private void onPosition(Session session, ClientboundPlayerPositionPacket p) {
        if (!p.getRelatives().isEmpty() && position != null) {
            // The bench never triggers relative teleports; keep the last absolute position.
            session.send(new ServerboundAcceptTeleportationPacket(p.getId()));
            return;
        }
        position = p.getPosition();
        yaw = p.getYRot();
        pitch = p.getXRot();
        session.send(new ServerboundAcceptTeleportationPacket(p.getId()));
        session.send(new ServerboundMovePlayerPosRotPacket(
                true, false, position.getX(), position.getY(), position.getZ(), yaw, pitch));
        if (!loadedSent) {
            loadedSent = true;
            session.send(ServerboundPlayerLoadedPacket.INSTANCE);
        }
        positioned.countDown();
    }

    @Override
    public void disconnected(DisconnectedEvent event) {
        if (!benchClosing) {
            disconnected = true;
            String reason = GsonComponentSerializer.gson().serialize(event.getReason());
            disconnectReason = event.getCause() == null ? reason : reason + " (" + event.getCause() + ")";
        }
        joined.countDown();
        positioned.countDown();
        closed.countDown();
    }
}
