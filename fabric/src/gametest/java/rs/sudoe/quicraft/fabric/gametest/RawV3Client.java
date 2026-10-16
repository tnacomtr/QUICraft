// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import rs.sudoe.quicraft.core.binding.BindingDriver;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.fabric.net.FrameCodec;

/**
 * A client of {@code quicraft/3} that speaks Minecraft's framing by hand and starts no game: login
 * (offline), the configuration stage with the binding exchange of docs/protocol.md 12 in client
 * role, the switch to the three lanes, Finish Configuration as a record, and then counting what the
 * server sends in play. It exists to drive a dedicated server's {@code /3} join end to end without
 * the client adapter. Packet ids are those of 26.1.x; they appear here only because this is a test.
 */
final class RawV3Client {
    /** What a join observed. */
    static final class Result {
        final List<String> events = new ArrayList<>();
        volatile boolean activated;
        volatile boolean finishedConfiguration;
        volatile int playPackets;
        volatile int configRecords;
        volatile Throwable failure;
        volatile boolean closed;
        volatile String closeCause;

        @Override
        public String toString() {
            return "activated=" + activated + " configRecords=" + configRecords + " finished="
                    + finishedConfiguration + " playPackets=" + playPackets + " closed=" + closed + " cause="
                    + closeCause + " failure=" + failure + " events=" + events;
        }
    }

    private static final int CLIENTBOUND_LOGIN_HELLO = 1;
    private static final int SERVERBOUND_LOGIN_KEY = 1;
    private static final int CLIENTBOUND_LOGIN_COMPRESSION = 3;
    private static final int CLIENTBOUND_LOGIN_FINISHED = 2;
    private static final int CLIENTBOUND_CONFIG_CUSTOM_PAYLOAD = 1;
    private static final int CLIENTBOUND_CONFIG_FINISH = 3;
    private static final int CLIENTBOUND_CONFIG_PING = 5;
    private static final int CLIENTBOUND_CONFIG_SELECT_KNOWN_PACKS = 14;
    private static final int SERVERBOUND_CONFIG_SELECT_KNOWN_PACKS = 7;
    private static final int SERVERBOUND_CONFIG_PONG = 5;
    private static final int SERVERBOUND_CONFIG_CUSTOM_PAYLOAD = 2;
    private static final int SERVERBOUND_CONFIG_FINISH = 3;
    private static final int SERVERBOUND_LOGIN_ACKNOWLEDGED = 3;
    private static final int PLAY_BUNDLE_DELIMITER = 0;
    private static final String BINDING = "quicraft:binding";

    private final V3Connection connection;
    private final RoutingTable table;
    private final byte[] fingerprint;
    private final Result result = new Result();
    private final CompletableFuture<Result> done = new CompletableFuture<>();
    private byte[] buffer = new byte[0];
    private int threshold = -1;
    private int phase; // 0 login, 1 configuration (binding), 2 lanes
    private BindingSession binding;
    private boolean inboundSwitched;
    private boolean registered;
    private boolean offerSeen;
    private ProtocolState laneState = ProtocolState.CONFIGURATION;
    private final int playPacketsWanted;
    private final boolean online;
    /** A join expected to fail: protocol surprises are noted, and only the close ends it. */
    private boolean lenient;
    private byte[] rawBuffer = new byte[0];
    private byte[] secret;
    private Cipher decryptIn;
    private Cipher encryptOut;

    private RawV3Client(V3Connection connection, RoutingTable table, byte[] fingerprint, int playPacketsWanted,
            boolean online) {
        this.online = online;
        this.connection = connection;
        this.table = table;
        this.fingerprint = fingerprint;
        this.playPacketsWanted = playPacketsWanted;
    }

    static final class Join implements AutoCloseable {
        final CompletableFuture<Result> done;
        final Result result;
        private final V3Connection connection;

        Join(CompletableFuture<Result> done, Result result, V3Connection connection) {
            this.done = done;
            this.result = result;
            this.connection = connection;
        }

        @Override
        public void close() {
            connection.close();
        }
    }

    /**
     * Starts a join. The caller must keep the game's ticks running (the client gametest harness steps
     * them) until {@link Join#done} completes: the server's login needs its main thread.
     */
    static Join start(InetSocketAddress server, Fingerprint fingerprint, RoutingTable table, int playPacketsWanted,
            boolean online) throws Exception {
        return start(server, fingerprint, table, playPacketsWanted, online, false);
    }

    static Join start(InetSocketAddress server, Fingerprint fingerprint, RoutingTable table, int playPacketsWanted,
            boolean online, boolean lenient) throws Exception {
        V3Connection connection = QuicClient.connectV3(server, fingerprint, table, null).get(15, TimeUnit.SECONDS);
        RawV3Client client = new RawV3Client(connection, table, digest(fingerprint), playPacketsWanted, online);
        client.lenient = lenient;
        connection.setListener(client.new Listener());
        connection.setAutoRead(true);
        client.send(handshake(server.getPort()));
        client.send(helloPacket());
        connection.flush();
        return new Join(client.done, client.result, connection);
    }

    private final class Listener implements QuicByteStream.Listener {
        @Override
        public void onData(ByteBuffer data) {
            try {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                if (phase >= 2) {
                    result.events.add("stray stream-0 bytes after activation: " + chunk.length);
                    return;
                }
                result.events.add("data " + chunk.length);
                rawBuffer = concat(rawBuffer, chunk);
                // CFB8 is byte for byte: the plaintext and the ciphertext it came from stay aligned, so
                // the ciphertext after a frame is still there when the cipher has to stop at it (12.13).
                buffer = concat(buffer, decryptIn == null ? chunk : decryptIn.update(chunk));
                parse();
            } catch (Throwable t) {
                if (lenient) {
                    result.events.add("ignored: " + t);
                    phase = 3;
                    return;
                }
                result.failure = t;
                done.complete(result);
            }
        }

        @Override
        public void onWritabilityChanged(boolean writable) { }

        @Override
        public void onClosed(Throwable cause) {
            result.closed = true;
            result.closeCause = String.valueOf(cause);
            done.complete(result);
        }
    }

    // --- Stream 0 as the game's byte stream, until the switch.

    private void parse() throws Exception {
        while (phase < 2) {
            int[] position = {0};
            long length;
            try {
                length = readVarInt(buffer, position);
            } catch (IndexOutOfBoundsException needMore) {
                return;
            }
            if (buffer.length - position[0] < length) {
                return;
            }
            int total = position[0] + (int) length;
            byte[] frame = Arrays.copyOf(buffer, total);
            buffer = Arrays.copyOfRange(buffer, total, buffer.length);
            rawBuffer = Arrays.copyOfRange(rawBuffer, total, rawBuffer.length);
            handleFrame(FrameCodec.decode(frame, threshold, false, (a, b) -> { }));
            if (inboundSwitched) {
                activateLanes();
                return;
            }
        }
    }

    private void handleFrame(byte[] packet) throws Exception {
        int[] position = {0};
        int id = (int) readVarInt(packet, position);
        if (phase == 0) {
            if (id == CLIENTBOUND_LOGIN_HELLO && online) {
                answerHello(packet, position);
            } else if (id == CLIENTBOUND_LOGIN_COMPRESSION) {
                threshold = (int) readVarInt(packet, position);
                result.events.add("compression " + threshold);
            } else if (id == CLIENTBOUND_LOGIN_FINISHED) {
                send(framed(new byte[] {(byte) SERVERBOUND_LOGIN_ACKNOWLEDGED}));
                connection.flush();
                phase = 1;
                result.events.add("login finished, configuration entered");
                binding = new BindingSession(BindingStateMachine.Role.CLIENT, connection::exporterValue, secret,
                        online ? BindingStateMachine.MODE_ONLINE : BindingStateMachine.MODE_OFFLINE, SharedConstants.getProtocolVersion(), fingerprint, table.digest(), System::nanoTime, 0L,
                        new BindingDriver.Callbacks() {
                            @Override
                            public void send(byte[] payload) {
                                RawV3Client.this.send(framed(customPayload(payload)));
                                connection.flush();
                            }

                            @Override
                            public void activateOutbound() {
                                // 12.11: the cipher stops right after the last encrypted byte.
                                encryptOut = null;
                                result.events.add("client switch sent");
                            }

                            @Override
                            public void activateInbound() {
                                inboundSwitched = true;
                                result.events.add("server switch verified");
                            }

                            @Override
                            public void fail(int code, String reason) {
                                result.failure = new IllegalStateException("binding failed: " + reason);
                                done.complete(result);
                            }
                        }, null, null);
                binding.onConfigurationEntered(System.nanoTime());
            } else {
                throw new IllegalStateException("unexpected login packet " + id);
            }
            return;
        }
        if (id == CLIENTBOUND_CONFIG_PING && !binding.isSettled() && !offerSeen) {
            // Fabric API pings the client to know its channel registration was processed; that is
            // before the server's configuration starts, so before OFFER. Pong it.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeVarInt(out, SERVERBOUND_CONFIG_PONG);
            out.write(packet, position[0], 4);
            send(framed(out.toByteArray()));
            connection.flush();
            result.events.add("ponged");
            return;
        }
        // Configuration, before the switch: only binding payloads may arrive (12.10).
        if (id != CLIENTBOUND_CONFIG_CUSTOM_PAYLOAD) {
            throw new IllegalStateException("12.10: an ordinary packet " + id + " arrived before activation");
        }
        int nameLength = (int) readVarInt(packet, position);
        String channel = new String(packet, position[0], nameLength, StandardCharsets.UTF_8);
        position[0] += nameLength;
        if ("minecraft:register".equals(channel) && !registered) {
            // Fabric API's channel registration is the one exchange that legitimately comes before
            // the server's configuration starts (and so before OFFER); answer like a client does.
            registered = true;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeVarInt(out, SERVERBOUND_CONFIG_CUSTOM_PAYLOAD);
            writeString(out, "minecraft:register");
            send(framed(out.toByteArray()));
            connection.flush();
            result.events.add("answered minecraft:register");
            return;
        }
        if (!BINDING.equals(channel)) {
            throw new IllegalStateException("12.10: payload on " + channel + " arrived before activation");
        }
        offerSeen = true;
        binding.onPayload(Arrays.copyOfRange(packet, position[0], packet.length));
    }

    private void activateLanes() {
        // The bytes after the final encrypted frame are the peer's first record, still ciphertext.
        byte[] rest = rawBuffer;
        buffer = new byte[0];
        rawBuffer = new byte[0];
        // Before anything else: from here stream 0 is the lane session's, and a byte the stream
        // still delivers to this listener must not be parsed as Minecraft frames.
        phase = 2;
        ByteBuffer[] core = new ByteBuffer[1];
        connection.detachForActivation(bytes -> {
            if (bytes != null && bytes.hasRemaining()) {
                ByteBuffer copy = ByteBuffer.allocate(bytes.remaining());
                copy.put(bytes).flip();
                core[0] = copy;
            }
        });
        int length = rest.length + (core[0] == null ? 0 : core[0].remaining());
        ByteBuffer leftover = null;
        if (length > 0) {
            leftover = ByteBuffer.allocate(length);
            leftover.put(rest);
            if (core[0] != null) {
                leftover.put(core[0]);
            }
            leftover.flip();
        }
        result.activated = true;
        result.events.add("lanes activated, leftover " + length + " bytes");
        connection.activate(new Game(), leftover);
    }

    // --- The lanes.

    private final class Game implements LaneGame {
        private boolean play;

        @Override
        public Decoded decode(Lane lane, long sequence, byte[] frame, boolean insideBundle, Reservation reservation)
                throws Exception {
            byte[] packet = FrameCodec.decode(frame, threshold, false, reservation::reserve);
            int id = (int) readVarInt(packet, new int[] {0});
            ProtocolState state = play ? ProtocolState.PLAY : ProtocolState.CONFIGURATION;
            Envelope.Bundle bundle = Envelope.Bundle.NONE;
            boolean change = false;
            if (state == ProtocolState.PLAY && id == PLAY_BUNDLE_DELIMITER) {
                bundle = insideBundle ? Envelope.Bundle.CLOSE : Envelope.Bundle.OPEN;
            } else if (insideBundle) {
                bundle = Envelope.Bundle.MEMBER;
            }
            if (state == ProtocolState.CONFIGURATION && id == CLIENTBOUND_CONFIG_FINISH) {
                change = true;
                play = true;
            }
            return new Decoded(id, state, id, bundle, change);
        }

        @Override
        public void deliver(Lane lane, long sequence, List<Object> packets, boolean bundle) {
            try {
                for (Object p : packets) {
                    int id = (Integer) p;
                    if (!result.finishedConfiguration) {
                        result.configRecords++;
                        if (id == CLIENTBOUND_CONFIG_SELECT_KNOWN_PACKS) {
                            // No known packs: the server sends the full registries.
                            connection.session().send(ProtocolState.CONFIGURATION, SERVERBOUND_CONFIG_SELECT_KNOWN_PACKS,
                                    framed(new byte[] {(byte) SERVERBOUND_CONFIG_SELECT_KNOWN_PACKS, 0}));
                        }
                        if (id == CLIENTBOUND_CONFIG_FINISH) {
                            result.finishedConfiguration = true;
                            result.events.add("finish configuration after " + result.configRecords + " records");
                            connection.session().send(ProtocolState.CONFIGURATION, SERVERBOUND_CONFIG_FINISH,
                                    framed(new byte[] {(byte) SERVERBOUND_CONFIG_FINISH}));
                        }
                    } else {
                        result.playPackets++;
                    }
                }
                connection.session().applied(lane, sequence);
                if (result.playPackets >= playPacketsWanted) {
                    done.complete(result);
                }
            } catch (Throwable t) {
                result.failure = t;
                done.complete(result);
            }
        }

        @Override
        public void onWritabilityChanged(boolean writable) { }

        @Override
        public void onClosed(int code, boolean byPeer, Throwable cause) {
            result.closed = true;
            result.closeCause = "code " + code + (byPeer ? " by peer " : " ") + cause;
            done.complete(result);
        }
    }

    // --- Minecraft bytes.

    private void send(byte[] bytes) {
        connection.write(ByteBuffer.wrap(encryptOut == null ? bytes : encryptOut.update(bytes)));
    }

    /** Vanilla's login encryption: RSA-encrypted secret and challenge, then AES/CFB8 both ways. */
    private void answerHello(byte[] packet, int[] position) throws Exception {
        int idLength = (int) readVarInt(packet, position);
        position[0] += idLength;
        int keyLength = (int) readVarInt(packet, position);
        byte[] publicKey = Arrays.copyOfRange(packet, position[0], position[0] + keyLength);
        position[0] += keyLength;
        int challengeLength = (int) readVarInt(packet, position);
        byte[] challenge = Arrays.copyOfRange(packet, position[0], position[0] + challengeLength);
        secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKey)));
        byte[] encryptedSecret = rsa.doFinal(secret);
        byte[] encryptedChallenge = rsa.doFinal(challenge);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, SERVERBOUND_LOGIN_KEY);
        writeVarInt(out, encryptedSecret.length);
        out.write(encryptedSecret, 0, encryptedSecret.length);
        writeVarInt(out, encryptedChallenge.length);
        out.write(encryptedChallenge, 0, encryptedChallenge.length);
        send(framed(out.toByteArray()));
        connection.flush();
        // Everything after the key packet is encrypted, in both directions.
        encryptOut = Cipher.getInstance("AES/CFB8/NoPadding");
        encryptOut.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
        decryptIn = Cipher.getInstance("AES/CFB8/NoPadding");
        decryptIn.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
        result.events.add("login encryption on");
    }

    private byte[] framed(byte[] packet) {
        try {
            return FrameCodec.encode(packet, threshold);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] handshake(int port) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0);
        writeVarInt(out, SharedConstants.getProtocolVersion());
        writeString(out, "127.0.0.1");
        out.write(port >> 8);
        out.write(port);
        writeVarInt(out, 2);
        return frameUncompressed(out.toByteArray());
    }

    private static byte[] helloPacket() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0);
        writeString(out, "RawV3");
        for (int i = 0; i < 16; i++) {
            out.write(i == 15 ? 7 : 0);
        }
        return frameUncompressed(out.toByteArray());
    }

    private static byte[] customPayload(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, SERVERBOUND_CONFIG_CUSTOM_PAYLOAD);
        writeString(out, BINDING);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] frameUncompressed(byte[] packet) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, packet.length);
        out.write(packet, 0, packet.length);
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & -128) != 0) {
            out.write(v & 0x7F | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    /** Throws {@link IndexOutOfBoundsException} when the bytes end inside the VarInt. */
    private static long readVarInt(byte[] data, int[] position) {
        long value = 0;
        for (int i = 0; i < 5; i++) {
            byte b = data[position[0]++];
            value |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalStateException("VarInt too big");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] digest(Fingerprint fingerprint) {
        String text = fingerprint.toString().substring("sha256:".length());
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
