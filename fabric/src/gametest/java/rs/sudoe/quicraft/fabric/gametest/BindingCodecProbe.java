// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.resources.Identifier;
import rs.sudoe.quicraft.core.binding.BindingFormatException;
import rs.sudoe.quicraft.core.binding.BindingMessage;
import rs.sudoe.quicraft.fabric.client.DecodeContext;
import rs.sudoe.quicraft.fabric.client.mixin.PacketDecoderAccessor;
import rs.sudoe.quicraft.fabric.client.mixin.PacketEncoderAccessor;
import rs.sudoe.quicraft.fabric.net.BindingDecodeContext;
import rs.sudoe.quicraft.fabric.net.BindingPayload;
import rs.sudoe.quicraft.fabric.net.FallbackPayload;

/** The codec-collision fix's runtime evidence: selection per connection, restoration and buffers. */
final class BindingCodecProbe {
    private static final int MAX_PAYLOAD = 32767;
    private static final String BINDING = BindingPayload.class.getName();
    private static final String DISCARDED = DiscardedPayload.class.getName();
    private static final String FALLBACK = FallbackPayload.class.getName();

    private BindingCodecProbe() {}

    /** The single prefix call in CausalSendGameTest.runTest, right after the runner's runId. */
    static void probe(ClientGameTestContext context, String runId) {
        context.runOnClient(client -> {
            check(client.getConnection() == null, "the fresh client thread has no existing connection");
            try { runOnClient(runId); } catch (Error error) { throw error; } catch (Throwable thrown) { throw new AssertionError(thrown); } });
    }

    private static void runOnClient(String runId) throws Throwable {
        List<String> cleanup = new ArrayList<>();
        List<EmbeddedChannel> owned = new ArrayList<>();
        Throwable primary = null;
        try {
            System.out.println("BINDING-CODEC-PROBE runId=" + runId + " path=prestate current=" + BindingDecodeContext.current() + " expected=" + DecodeContext.bindingExpected());
            check(BindingDecodeContext.current() == null, "a foreign decode context lingers on this client thread");
            check(!DecodeContext.bindingExpected(), "binding unexpectedly expected before the probe");
            try {
                // Every channel the probe builds is owned from birth: it is registered right after construction,
                // before any pipeline or wire configure, and the finally below finishes all of them exactly once,
                // newest first. A finish failure is recorded in cleanup and can never mask the probe's own error.
                EmbeddedChannel client = new EmbeddedChannel();
                owned.add(client);
                client.pipeline().addLast(new Gate());
                EmbeddedChannel server = new EmbeddedChannel();
                owned.add(server);
                server.attr(BindingDecodeContext.SERVER_GATE).set(() -> true);
                EmbeddedChannel ungated = new EmbeddedChannel();
                owned.add(ungated);
                EmbeddedChannel wire = wireEncoder(owned);
                @SuppressWarnings("rawtypes") StreamCodec shared = DiscardedPayload.<FriendlyByteBuf>codec(BindingPayload.TYPE.id(), MAX_PAYLOAD);
                byte[] body = content(104, 42);
                byte[] frame104 = packetBytes(runId, wire, content(104, 42), cleanup);
                byte[] frame106 = packetBytes(runId, wire, content(106, 42), cleanup);
                roles(runId, client, server, ungated, shared, body, cleanup);
                sizeRules(runId, client, shared, cleanup);
                decoders(runId, frame104, frame106, body, cleanup, owned);
            } finally {
                for (int i = owned.size() - 1; i >= 0; i--) { finish(owned.get(i), cleanup); }
                DecodeContext.enter(null);
            }
        } catch (Throwable thrown) { primary = thrown; }
        if (primary != null) {
            System.out.println("BINDING-CODEC-PROBE runId=" + runId + " path=throw thrown=" + primary + (cleanup.isEmpty() ? " cleanup=clean" : " cleanup=" + cleanup));
            throw primary;
        }
        check(cleanup.isEmpty(), "cleanup failed after the probe: " + cleanup);
        System.out.println("BINDING-CODEC-PROBE runId=" + runId + " path=done class=BindingCodecProbe");
    }

    /** Every decode selects per connection right now; the same cached codec covers all roles. */
    private static void roles(String runId, EmbeddedChannel client, EmbeddedChannel server, EmbeddedChannel ungated, StreamCodec shared, byte[] body, List<String> cleanup) throws Exception {
        String[] tags = {"client", "server", "ungated"};
        for (int i = 0; i < tags.length; i++) {
            if (i == 0) { DecodeContext.enter(client); } // the client facade enters the pipeline-gate channel
            else { BindingDecodeContext.enter(i == 1 ? server : ungated); } // the attr gate / no gate at all
            String expected = i == 2 ? DISCARDED : BINDING;
            verdict(runId, "codec:" + tags[i], buf -> shared.decode(buf), expected, body, cleanup);
            configDecode(runId, "config:" + tags[i], body, expected, cleanup);
        }
        BindingDecodeContext.enter(null);
        verdict(runId, "codec:null", buf -> shared.decode(buf), DISCARDED, body, cleanup);
        configDecode(runId, "config:null", body, DISCARDED, cleanup);
        sharedEncode(runId, shared, body, cleanup);
        verdict(runId, "fallback:null", buf -> DiscardedPayload.<FriendlyByteBuf>codec(FallbackPayload.TYPE.id(), MAX_PAYLOAD).decode(buf), FALLBACK, body, cleanup);
        DecodeContext.enter(client); // re-enabling the gate flips the very next decode back on
        verdict(runId, "codec:client-again", buf -> shared.decode(buf), BINDING, body, cleanup);
        // other ids stay vanilla even while an exchange is expected on this connection
        verdict(runId, "codec:unknown", buf -> DiscardedPayload.<FriendlyByteBuf>codec(Identifier.parse("minecraft:brand"), MAX_PAYLOAD).decode(buf), DISCARDED, body, cleanup);
        BindingDecodeContext.enter(null);
        smallMax(runId, cleanup);
    }

    private static void configDecode(String runId, String path, byte[] body, String expected, List<String> cleanup) throws Exception {
        FriendlyByteBuf config = new FriendlyByteBuf(Unpooled.buffer());
        config.writeUtf(BindingPayload.CHANNEL); config.writeBytes(body);
        verdict(runId, path, cfg -> ClientboundCustomPayloadPacket.CONFIG_STREAM_CODEC.decode(cfg).payload(), expected, body, cleanup, config);
    }

    /** One decode call: the probe hands the decode the single buffer it owns and releases that exact object. */
    @FunctionalInterface
    private interface Decode {
        Object decode(FriendlyByteBuf buf) throws Exception;
    }

    /** Allocates the probe's one decode buffer, runs the codec on that exact object, releases it in finally, and asserts the class. */
    private static void verdict(String runId, String path, Decode run, String expected, byte[] body, List<String> cleanup) throws Exception {
        verdict(runId, path, run, expected, body, cleanup, new FriendlyByteBuf(Unpooled.wrappedBuffer(body)));
    }

    /** Runs the codec against the caller-owned buffer the decode reads from, releases that same buffer, and asserts the class. */
    private static void verdict(String runId, String path, Decode run, String expected, byte[] body, List<String> cleanup, FriendlyByteBuf buf) throws Exception {
        try {
            Object decoded = run.decode(buf);
            System.out.println("BINDING-CODEC-PROBE runId=" + runId + " path=" + path + " class=" + decoded.getClass().getName());
            check(expected.equals(decoded.getClass().getName()), path + ": " + decoded.getClass().getName());
            if (decoded instanceof BindingPayload payload) { check(Arrays.equals(body, payload.data()), path + ": the payload bytes changed"); }
            else if (decoded instanceof FallbackPayload payload) { check(Arrays.equals(body, payload.data()), path + ": the fallback payload bytes changed"); }
        } finally { retainedOnce(buf, cleanup); }
    }

    /** The shared codec's encode side is unconditional: it writes the exact payload body. */
    private static void sharedEncode(String runId, StreamCodec shared, byte[] body, List<String> cleanup) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            shared.encode(buf, new BindingPayload(body));
            byte[] encoded = ByteBufUtil.getBytes(buf);
            System.out.println("BINDING-CODEC-PROBE runId=" + runId + " path=encode:null class=BindingPayload(bytes " + encoded.length + ")");
            check(Arrays.equals(body, encoded), "the shared encode wrote " + encoded.length + " unexpected bytes");
        } finally { retainedOnce(buf, cleanup); }
    }

    /** Gated sizes 0..105 keep their bytes; 106 fails with the shared codec's exact message. */
    private static void sizeRules(String runId, EmbeddedChannel client, StreamCodec shared, List<String> cleanup) throws Exception {
        BindingDecodeContext.enter(client);
        for (int length : new int[] {0, 103, 104, 105}) {
            byte[] data = content(length, 42);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(Math.max(1, data.length)).writeBytes(data));
            verdict(runId, "sizes:" + length, b -> shared.decode(b), BINDING, data, cleanup, buf);
            if (length != 104) { check(caught(() -> BindingMessage.decode(data)) instanceof BindingFormatException, "length " + length + ": the validator did not reject a non-104-byte payload"); }
        }
        FriendlyByteBuf oversized = new FriendlyByteBuf(Unpooled.wrappedBuffer(content(106, 42)));
        try {
            Object rejected = caught(() -> shared.decode(oversized));
            System.out.println("BINDING-CODEC-PROBE runId=" + runId + " path=sizes:106 threw=" + rejected);
            check(rejected instanceof IllegalArgumentException tooLarge && "quicraft:binding payload too large: 106".equals(tooLarge.getMessage()), "size 106: " + rejected);
        } finally { retainedOnce(oversized, cleanup); }
        BindingDecodeContext.enter(null);
    }

    private static void smallMax(String runId, List<String> cleanup) {
        FriendlyByteBuf oursBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(content(17, 42)));
        Object ours = attempted(() -> DiscardedPayload.<FriendlyByteBuf>codec(BindingPayload.TYPE.id(), 16).decode(oursBuf), oursBuf, cleanup);
        FriendlyByteBuf theirsBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[17]));
        Object theirs = attempted(() -> DiscardedPayload.<FriendlyByteBuf>codec(Identifier.parse("minecraft:brand"), 16).decode(theirsBuf), theirsBuf, cleanup);
        System.out.println("BINDING-CODEC-PROBE runId=" + runId + " path=small-max ours=" + ours + " theirs=" + theirs);
        check(ours instanceof IllegalArgumentException first && theirs instanceof IllegalArgumentException second && String.valueOf(first.getMessage()).equals(String.valueOf(second.getMessage())), "the over-long payloads throw differently: " + ours + " !== " + theirs);
    }

    /** The real transformed decoder rigs: success, failure-restore, nest and the ungated twin. */
    private static void decoders(String runId, byte[] frame104, byte[] frame106, byte[] body, List<String> cleanup, List<EmbeddedChannel> owned) throws Exception {
        Rig gated = rig(ConfigurationProtocols.CLIENTBOUND, true, owned);
        round(gated, frame104, "decoder:gated", runId, cleanup, null, BINDING, body);
        failure(gated, frame106, runId, cleanup); // the next decode on the same rig must work again
        round(gated, frame104, "decoder:recovery", runId, cleanup, null, BINDING, body);
        nested(frame104, body, runId, cleanup, owned);
        round(rig(ConfigurationProtocols.CLIENTBOUND, false, owned), frame104, "decoder:ungated", runId, cleanup, null, DISCARDED, body);
    }

    private static void nested(byte[] frame, byte[] body, String runId, List<String> cleanup, List<EmbeddedChannel> owned) throws Exception {
        Rig inner = rig(ConfigurationProtocols.CLIENTBOUND, true, owned);
        Rig[] outer = new Rig[1]; AtomicInteger taps = new AtomicInteger();
        outer[0] = rig(tapAdapter(inner, outer, taps, frame, body, runId, cleanup), true, owned);
        List<Object> out = invoked(outer[0], frame, "decoder:nested", runId, cleanup);
        check(taps.get() == 1, "the nested decoder inside the outer decode ran " + taps.get() + " times, not once");
        check(BindingDecodeContext.current() == null, "the outer decode left the thread's previous context at " + BindingDecodeContext.current());
        check(out.size() == 1 && out.get(0) instanceof ClientboundCustomPayloadPacket packet && packet.payload() instanceof BindingPayload payload && Arrays.equals(body, payload.data()), "the outer decode lost the payload bytes: " + out);
    }

    private static ClientboundCustomPayloadPacket round(Rig rig, byte[] frame, String path, String runId, List<String> cleanup, Channel restored, String expected, byte[] body) throws Exception {
        List<Object> out = invoked(rig, frame, path, runId, cleanup);
        check(BindingDecodeContext.current() == restored, path + ": the context is " + BindingDecodeContext.current() + " instead of " + restored);
        ClientboundCustomPayloadPacket decoded = (ClientboundCustomPayloadPacket) out.get(0);
        System.out.println("BINDING-CODEC-PROBE runId=" + runId + " path=" + path + " class=" + decoded.payload().getClass().getName());
        check(expected.equals(decoded.payload().getClass().getName()), path + ": " + decoded.payload());
        if (expected.equals(BINDING)) { check(decoded.payload() instanceof BindingPayload payload && Arrays.equals(body, payload.data()), path + ": the packet's payload bytes changed"); }
        return decoded;
    }

    private static void failure(Rig rig, byte[] frame, String runId, List<String> cleanup) throws Exception {
        ByteBuf in = Unpooled.wrappedBuffer(frame);
        List<Object> out = new ArrayList<>(1);
        Throwable thrown = null;
        try { ((PacketDecoderAccessor) rig.decoder()).quicraft$decode(rig.context(), in, out); }
        catch (Throwable error) { thrown = error; }
        finally { retainedOnce(in, cleanup); }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Throwable step = thrown; step != null && seen.add(step.toString()); step = step.getCause()) {} String chain = String.join(" <- ", seen);
        System.out.println("BINDING-CODEC-PROBE runId=" + runId + " path=decoder:too-long threw=" + chain);
        check(thrown != null, "the over-long payload decoded instead of failing: " + out);
        check(chain.contains("quicraft:binding payload too large: 106"), "the decode lost the real failure: " + chain);
        check(BindingDecodeContext.current() == null, "the failed decode left the context entered");
    }

    private static void nestedTap(Rig inner, byte[] frame, byte[] body, Channel restored, String runId, List<String> cleanup) throws Exception {
        round(inner, frame, "decoder:nested", runId, cleanup, restored, BINDING, body);
    }

    /** Delegates the actual protocol's every method; the codec additionally drives a nested decode. */
    private static ProtocolInfo tapAdapter(Rig inner, Rig[] outer, AtomicInteger taps, byte[] frame, byte[] body, String runId, List<String> cleanup) {
        ProtocolInfo real = ConfigurationProtocols.CLIENTBOUND; StreamCodec realCodec = real.codec();
        return new ProtocolInfo() {
            @Override public ConnectionProtocol id() { return real.id(); }
            @Override public PacketFlow flow() { return real.flow(); }
            @Override public StreamCodec codec() {
                return new StreamCodec<ByteBuf, Object>() {
                    @Override public Object decode(ByteBuf buffer) {
                        taps.getAndIncrement();
                        try { nestedTap(inner, frame, body, outer[0].channel(), runId, cleanup); }
                        catch (Exception error) { throw new IllegalStateException("the nested decode failed", error); }
                        check(BindingDecodeContext.current() == outer[0].channel(), "the nested decode replaced the outer context with " + BindingDecodeContext.current());
                        check(DecodeContext.bindingExpected(), "the exchange is no longer expected after the nested decode");
                        return realCodec.decode(buffer);
                    }
                    @Override public void encode(ByteBuf buffer, Object value) { realCodec.encode(buffer, value); }
                };
            }
            @Override public BundlerInfo bundlerInfo() { return real.bundlerInfo(); }
        };
    }

    private record Rig(EmbeddedChannel channel, ChannelHandlerContext context, PacketDecoder<?> decoder) {}

    @SuppressWarnings("unchecked")
    private static Rig rig(ProtocolInfo protocol, boolean gated, List<EmbeddedChannel> owned) {
        EmbeddedChannel channel = new EmbeddedChannel();
        owned.add(channel); // owned before any pipeline work below can fail
        if (gated) { channel.pipeline().addLast(new Gate()); }
        PacketDecoder<?> decoder = new PacketDecoder<>(protocol);
        channel.pipeline().addLast("decoder", decoder);
        return new Rig(channel, channel.pipeline().context(decoder), decoder);
    }

    /** Runs the game's own transformed decode on one owned frame; releases it and asserts one packet. */
    private static List<Object> invoked(Rig rig, byte[] frame, String path, String runId, List<String> cleanup) throws Exception {
        ByteBuf in = Unpooled.wrappedBuffer(frame);
        List<Object> out = new ArrayList<>(1);
        try { ((PacketDecoderAccessor) rig.decoder()).quicraft$decode(rig.context(), in, out); }
        finally { retainedOnce(in, cleanup); }
        check(out.size() == 1, path + ": the decoder produced " + out.size() + " objects: " + out);
        return out;
    }

    private static EmbeddedChannel wireEncoder(List<EmbeddedChannel> owned) {
        EmbeddedChannel wire = new EmbeddedChannel();
        owned.add(wire);
        Connection.configureSerialization(wire.pipeline(), PacketFlow.SERVERBOUND, false, null);
        wire.pipeline().replace("outbound_config", "encoder", new PacketEncoder<>(ConfigurationProtocols.CLIENTBOUND));
        return wire;
    }
    private static byte[] packetBytes(String runId, EmbeddedChannel wire, byte[] body, List<String> cleanup) {
        ByteBuf out = Unpooled.buffer();
        try {
            ((PacketEncoderAccessor) wire.pipeline().get("encoder")).quicraft$encode(wire.pipeline().context("encoder"), new ClientboundCustomPayloadPacket(new BindingPayload(body)), out);
            byte[] encoded = ByteBufUtil.getBytes(out);
            System.out.println("BINDING-CODEC-PROBE runId=" + runId + " path=wire class=BindingPacket(bytes " + encoded.length + ")");
            return encoded;
        } catch (Throwable thrown) { throw new AssertionError("encoding the binding packet through the game's encoder failed", thrown); }
        finally { retainedOnce(out, cleanup); } }

    private static byte[] content(int length, int mark) {
        byte[] result = new byte[length];
        if (length > 0) { result[0] = (byte) mark; }
        if (length > 5) { result[5] = 42; }
        return result; }
    private static Object caught(Callable<Object> run) {
        try { return run.call(); } catch (Throwable thrown) { return thrown; } }
    /** Reports the outcome of one decode against an owned buffer and releases that buffer afterwards. */
    private static Object attempted(Callable<Object> run, ByteBuf buf, List<String> cleanup) {
        try { return run.call(); } catch (Throwable thrown) { return thrown; } finally { retainedOnce(buf, cleanup); } }
    /** The probe owns these buffers; releasing once is asserted, its errors never mask the primary ones. */
    private static void retainedOnce(ByteBuf buffer, List<String> cleanup) {
        try { check(buffer.refCnt() == 1, "a decode buffer ended with " + buffer.refCnt() + " references instead of the owner's one"); buffer.release(); check(buffer.refCnt() == 0, "a released buffer still holds references"); }
        catch (Throwable thrown) { cleanup.add(thrown.toString()); } }
    /** Finishes one owned embedded channel exactly once; its errors land in cleanup and never mask a primary error. */
    private static void finish(EmbeddedChannel channel, List<String> cleanup) {
        try { channel.finishAndReleaseAll(); } catch (Throwable thrown) { cleanup.add(thrown.toString()); } }
    /** The one handler of the stand-in channels: the pipeline sees a gate expecting a binding. */
    private static final class Gate extends ChannelInboundHandlerAdapter implements DecodeContext.Gate {
        @Override public boolean expectsBinding() { return true; }
    }
}
