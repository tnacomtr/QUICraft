// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.bridge.BindingPayloads;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.core.binding.BindingDriver;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.multistream.MultistreamSession;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.resource.OutboundPool;
import rs.sudoe.quicraft.core.multistream.OutboundPacket;
import rs.sudoe.quicraft.core.resource.PressureRanking;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.multistream.SentPacket;
import rs.sudoe.quicraft.core.profile.ServerProfile;
import rs.sudoe.quicraft.core.multistream.SessionContext;
import rs.sudoe.quicraft.core.multistream.SessionSendDriver;
import rs.sudoe.quicraft.core.multistream.StreamSequencer;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.fabric.net.PacketLaneCodec;

class V3CausalSubmissionTest {
    private enum EncoderMode { OUTPUT, REFUSE, EMPTY }

    /** The only mocked stage is the external Minecraft encoder boundary. */
    private static final class ExternalEncoder extends ChannelOutboundHandlerAdapter {
        final EncoderMode mode;
        ChannelPromise seenPromise;

        ExternalEncoder(EncoderMode mode) { this.mode = mode; }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!(msg instanceof Packet)) {
                ctx.write(msg, promise);
                return;
            }
            seenPromise = promise;
            if (mode == EncoderMode.REFUSE) {
                promise.tryFailure(new IllegalStateException("fixture encoder refusal"));
            } else if (mode == EncoderMode.EMPTY) {
                promise.trySuccess();
            } else {
                ctx.write(Unpooled.wrappedBuffer(new byte[] {1, 0x25}), promise);
            }
        }
    }

    private static final class CoreRig {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        final PressureRanking ranking = new PressureRanking();
        final Object owner = new Object();
        final OutboundPool pool;
        final SessionSendDriver driver;

        CoreRig() throws Exception {
            NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
            pool = new OutboundPool(node, 1L << 40, 1L << 40, 1L << 40, ranking.view(now::get));
            ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4);
            driver = new SessionSendDriver(SessionContext.of(1), profile, pool, ranking, owner,
                    InetAddress.getLoopbackAddress(), null, now::get);
        }
    }

    private static final class SubmissionRig {
        final CoreRig core;
        final AtomicInteger covered = new AtomicInteger();
        final AtomicInteger ordinary = new AtomicInteger();
        final MultistreamSession session;
        final CausalSendContext context;
        final V3Connection transport;
        final V3Join join;

        SubmissionRig(CoreRig core) throws Exception {
            this.core = core;
            session = driverAdapter(core.driver, covered, ordinary);
            QuicBridgeChannel bridge = new QuicBridgeChannel((QuicByteStream) Proxy.newProxyInstance(
                    QuicByteStream.class.getClassLoader(), new Class<?>[] {QuicByteStream.class},
                    (p, m, a) -> null));
            transport = (V3Connection) Proxy.newProxyInstance(V3Connection.class.getClassLoader(),
                    new Class<?>[] {V3Connection.class}, (p, m, a) -> m.getName().equals("session") ? session : null);
            context = new CausalSendContext(new Object());
            CausalSendContext.attach(bridge, context);
            context.bindTransport(transport);
            context.bindSession(transport, session);
            Constructor<V3Join> constructor = V3Join.class.getDeclaredConstructor(QuicBridgeChannel.class,
                    V3Connection.class, net.minecraft.server.MinecraftServer.class, byte[].class, ReceiveBudget.class);
            constructor.setAccessible(true);
            join = constructor.newInstance(bridge, transport, null, new byte[32], ReceiveBudget.unlimited());
            set(join, "causal", context);
            set(join, "codec", new PacketLaneCodec(new EmbeddedChannel().pipeline()));
        }

        OutboundUnit coveredUnit(MultistreamSession.UpdateToken token) {
            OutboundUnit unit = new OutboundUnit(false, context.newEnvelope(null,
                    new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(token), null, null),
                    CausalSendEnvelope.BundleMetadata.NONE));
            unit.add(ProtocolState.PLAY, new byte[] {1, 0x25});
            return unit;
        }
    }

    /** Real join handlers, BindingPayloads, BindingSession and core driver; only external I/O is fake. */
    private static final class FilterRig {
        final CoreRig core = new CoreRig();
        final AtomicInteger covered = new AtomicInteger();
        final AtomicInteger ordinary = new AtomicInteger();
        final AtomicInteger aborts = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Runnable> afterOrdinary =
                new java.util.concurrent.atomic.AtomicReference<>(() -> { });
        final MultistreamSession coreSession = driverAdapter(core.driver, covered, ordinary, afterOrdinary::get);
        final java.util.concurrent.atomic.AtomicReference<MultistreamSession> attachedCore =
                new java.util.concurrent.atomic.AtomicReference<>();
        final QuicBridgeChannel bridge = new QuicBridgeChannel((QuicByteStream) Proxy.newProxyInstance(
                QuicByteStream.class.getClassLoader(), new Class<?>[] {QuicByteStream.class}, (p, m, a) -> null));
        final V3Connection transport = (V3Connection) Proxy.newProxyInstance(V3Connection.class.getClassLoader(),
                new Class<?>[] {V3Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("session")) return attachedCore.get();
                    if (m.getName().equals("abort")) { aborts.incrementAndGet(); core.driver.close(); return null; }
                    return defaultValue(m.getReturnType());
                });
        final CausalSendContext context = new CausalSendContext(new Object());
        final ReceiveBudget node = ReceiveBudget.bounded(4096);
        final EmbeddedChannel channel = new EmbeddedChannel();
        final V3Join join;
        final ExternalEncoder encoder;
        final BindingSession binding;
        final BindingPayloads payloads;

        FilterRig(EncoderMode mode, boolean active) throws Exception { this(mode, active, true); }

        FilterRig(EncoderMode mode, boolean active, boolean bindCore) throws Exception {
            CausalSendContext.attach(bridge, context);
            CausalSendContext.attach(channel, context);
            context.bindTransport(transport);
            if (bindCore) {
                attachedCore.set(coreSession);
                context.bindSession(transport, coreSession);
            }
            Constructor<V3Join> constructor = V3Join.class.getDeclaredConstructor(QuicBridgeChannel.class,
                    V3Connection.class, net.minecraft.server.MinecraftServer.class, byte[].class, ReceiveBudget.class);
            constructor.setAccessible(true);
            join = constructor.newInstance(bridge, transport, null, new byte[32], node);
            set(join, "causal", context);
            set(join, "codec", new PacketLaneCodec(channel.pipeline()));
            set(join, "outboundState", ProtocolState.PLAY);
            set(join, "sinkActive", active);
            binding = new BindingSession(BindingStateMachine.Role.SERVER, () -> new byte[32], null,
                    BindingStateMachine.MODE_OFFLINE, 775, new byte[32], new byte[32], System::nanoTime, 0L,
                    new BindingDriver.Callbacks() {
                        @Override public void send(byte[] payload) { }
                        @Override public void activateOutbound() { }
                        @Override public void activateInbound() { }
                        @Override public void fail(int code, String reason) { }
                    }, null, null);
            payloads = new BindingPayloads(binding, new BindingDriver.Callbacks() {
                @Override public void send(byte[] payload) { }
                @Override public void activateOutbound() { }
                @Override public void activateInbound() { }
                @Override public void fail(int code, String reason) { }
            }, payload -> { });
            set(join, "session", binding);
            set(join, "payloads", payloads);
            encoder = new ExternalEncoder(mode);
            channel.pipeline().addLast("quicraft_lane_sink", (io.netty.channel.ChannelHandler) inner(join, "EncoderSink"));
            channel.pipeline().addLast("encoder", encoder);
            channel.pipeline().addLast("quicraft_lane_out",
                    (io.netty.channel.ChannelHandler) inner(join, "OutboundFilter", active));
        }

        void close() {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void actualV3ConsumerAssignsOneCoveredPacketAndRejectsResolvedOrForeignTokens() throws Exception {
        CoreRig core = new CoreRig();
        MultistreamSession.CausalScope scope = core.driver.openScope();
        MultistreamSession.UpdateToken token = core.driver.registerUpdate(scope);
        AtomicInteger covered = new AtomicInteger();
        AtomicInteger ordinary = new AtomicInteger();
        MultistreamSession session = driverAdapter(core.driver, covered, ordinary);
        QuicBridgeChannel bridge = new QuicBridgeChannel((QuicByteStream) Proxy.newProxyInstance(
                QuicByteStream.class.getClassLoader(), new Class<?>[] {QuicByteStream.class}, (p, m, a) -> null));
        V3Connection transport = (V3Connection) Proxy.newProxyInstance(V3Connection.class.getClassLoader(),
                new Class<?>[] {V3Connection.class}, (p, m, a) -> m.getName().equals("session") ? session : null);
        CausalSendContext context = new CausalSendContext(new Object());
        CausalSendContext.attach(bridge, context);
        context.bindTransport(transport);
        context.bindSession(transport, session);

        Constructor<V3Join> constructor = V3Join.class.getDeclaredConstructor(QuicBridgeChannel.class,
                V3Connection.class, net.minecraft.server.MinecraftServer.class, byte[].class, ReceiveBudget.class);
        constructor.setAccessible(true);
        V3Join join = constructor.newInstance(bridge, transport, null, new byte[32], ReceiveBudget.unlimited());
        set(join, "causal", context);
        set(join, "codec", new PacketLaneCodec(new EmbeddedChannel().pipeline()));

        CausalSendEnvelope coveredEnvelope = context.newEnvelope(null,
                new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(token), null, null),
                CausalSendEnvelope.BundleMetadata.NONE);
        OutboundUnit one = new OutboundUnit(false, coveredEnvelope);
        one.add(ProtocolState.PLAY, new byte[] {1, 0x25});
        invokeSubmit(join, one);
        assertEquals(1, covered.get(), "actual V3 consumer calls sendCovered once");
        assertEquals(0, ordinary.get(), "covered packet never falls through to ordinary send");
        assertEquals(1, core.driver.queuedRecords(), "the real core driver assigned one record");
        core.driver.completeScope(scope);
        assertEquals(0, core.driver.trackerRetainedTokens(), "resolved token permits completing its real tracker scope");
        assertFalse(core.driver.isClosed());

        OutboundUnit resolvedAgain = coveredUnit(context, token);
        assertThrows(IllegalArgumentException.class, () -> invokeSubmit(join, resolvedAgain));
        assertEquals(1, core.driver.queuedRecords(), "resolved-token rejection assigns no extra record");

        CoreRig other = new CoreRig();
        MultistreamSession.CausalScope otherScope = other.driver.openScope();
        MultistreamSession.UpdateToken foreignToken = other.driver.registerUpdate(otherScope);
        OutboundUnit foreignAgain = coveredUnit(context, foreignToken);
        assertThrows(IllegalArgumentException.class, () -> invokeSubmit(join, foreignAgain));
        assertEquals(1, core.driver.queuedRecords(), "foreign-token rejection assigns no record");
    }

    @Test
    void v3ConsumerRejectsZeroOutputCoverageAndUncoveredEmptyUnits() throws Exception {
        CoreRig core = new CoreRig();
        MultistreamSession.CausalScope scope = core.driver.openScope();
        MultistreamSession.UpdateToken token = core.driver.registerUpdate(scope);
        AtomicInteger covered = new AtomicInteger();
        AtomicInteger ordinary = new AtomicInteger();
        MultistreamSession session = driverAdapter(core.driver, covered, ordinary);
        QuicBridgeChannel bridge = new QuicBridgeChannel((QuicByteStream) Proxy.newProxyInstance(
                QuicByteStream.class.getClassLoader(), new Class<?>[] {QuicByteStream.class}, (p, m, a) -> null));
        V3Connection transport = (V3Connection) Proxy.newProxyInstance(V3Connection.class.getClassLoader(),
                new Class<?>[] {V3Connection.class}, (p, m, a) -> m.getName().equals("session") ? session : null);
        CausalSendContext context = new CausalSendContext(new Object());
        CausalSendContext.attach(bridge, context);
        context.bindTransport(transport);
        context.bindSession(transport, session);
        Constructor<V3Join> constructor = V3Join.class.getDeclaredConstructor(QuicBridgeChannel.class,
                V3Connection.class, net.minecraft.server.MinecraftServer.class, byte[].class, ReceiveBudget.class);
        constructor.setAccessible(true);
        V3Join join = constructor.newInstance(bridge, transport, null, new byte[32], ReceiveBudget.unlimited());
        set(join, "causal", context);
        set(join, "codec", new PacketLaneCodec(new EmbeddedChannel().pipeline()));
        OutboundUnit emptyCovered = new OutboundUnit(false, context.newEnvelope(null,
                new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(token), null, null),
                CausalSendEnvelope.BundleMetadata.NONE));
        assertThrows(IllegalStateException.class, () -> invokeSubmit(join, emptyCovered));
        assertEquals(0, covered.get());
        assertEquals(0, ordinary.get());
        assertEquals(0, core.driver.queuedRecords());
        assertFalse(core.driver.isClosed());

        core.driver.close();
        OutboundUnit coreClosed = coveredUnit(context, token);
        assertThrows(IllegalStateException.class, () -> invokeSubmit(join, coreClosed));
        assertEquals(1, covered.get(), "closed core was reached through actual sendCovered, not ordinary send");
        assertEquals(0, ordinary.get());
        assertEquals(0, core.driver.queuedRecords());
    }

    @Test
    void actualV3ConsumerRejectsEveryUnsupportedCoverageShapeBeforeAnyAssignmentOrCharge() throws Exception {
        CoreRig core = new CoreRig();
        SubmissionRig rig = new SubmissionRig(core);
        MultistreamSession.CausalScope scope = core.driver.openScope();
        MultistreamSession.UpdateToken one = core.driver.registerUpdate(scope);
        MultistreamSession.UpdateToken two = core.driver.registerUpdate(scope);
        List<OutboundUnit> unsupported = List.of(
                coveredUnit(rig, List.of(one, two), List.of(), null, null, false, 1, 1),
                coveredUnit(rig, List.of(one), List.of(scope), null, null, false, 1),
                coveredUnit(rig, List.of(one), List.of(), null, null, true, 1, 1),
                coveredUnit(rig, List.of(one), List.of(), null, null, false, 1, 1),
                coveredUnit(rig, List.of(one), List.of(), new CausalSendEnvelope.AckOperation(1, 0, List.of()),
                        null, false, 1),
                coveredUnit(rig, List.of(one), List.of(), null,
                        new CausalSendEnvelope.BatchOperation(1, CausalSendEnvelope.BatchKind.START, 0, 1), false, 1),
                coveredUnit(rig, List.of(one), List.of(), null, null, false));
        // Last split member has a truncated packet-id VarInt. Full-unit validation must reject it
        // before the valid first member is assigned.
        OutboundUnit invalidLast = new OutboundUnit(false, rig.context.newEnvelope(null,
                CausalSendEnvelope.ProducerSnapshot.EMPTY, CausalSendEnvelope.BundleMetadata.NONE));
        invalidLast.add(ProtocolState.PLAY, new byte[] {1, 0x25});
        invalidLast.add(ProtocolState.PLAY, new byte[] {(byte) 0x80});
        unsupported = new java.util.ArrayList<>(unsupported);
        unsupported.add(invalidLast);

        int pendingTokens = core.driver.trackerRetainedTokens();
        long poolCharge = core.pool.reserved(core.owner);
        int queued = core.driver.queuedRecords();
        for (OutboundUnit unit : unsupported) {
            assertThrows(Exception.class, () -> invokeSubmit(rig.join, unit));
            assertEquals(0, rig.covered.get(), "unsupported covered shape never calls sendCovered");
            assertEquals(0, rig.ordinary.get(), "unsupported shape never falls back to ordinary send");
            assertEquals(queued, core.driver.queuedRecords(), "unsupported shape receives no core sequence");
            assertEquals(poolCharge, core.pool.reserved(core.owner), "unsupported shape incurs no pool charge");
            assertEquals(pendingTokens, core.driver.trackerRetainedTokens(), "refusal leaves producer tokens unsettled");
        }
        core.driver.close();
    }

    @Test
    void realFilterCarriesEnvelopeIntoBindingQueueAndTeardownReleasesItsChargeOnce() throws Exception {
        FilterRig rig = new FilterRig(EncoderMode.OUTPUT, false);
        rig.payloads.onConfigurationEntered(System.nanoTime());
        CausalSendEnvelope envelope = rig.context.newEnvelope(null, CausalSendEnvelope.ProducerSnapshot.EMPTY,
                CausalSendEnvelope.BundleMetadata.NONE);
        ChannelPromise promise = rig.channel.newPromise();
        CausalSendContext.Frame frame = rig.context.enter(envelope);
        try { rig.channel.write(new ClientboundBlockChangedAckPacket(0), promise); }
        finally { rig.context.exit(frame); }
        assertSame(promise, rig.encoder.seenPromise, "filter passes the original promise to the encoder");
        assertTrue(promise.isSuccess());
        List<Object> queued = rig.payloads.releaseQueued();
        assertEquals(1, queued.size());
        OutboundUnit retained = (OutboundUnit) queued.get(0);
        assertSame(envelope, retained.envelope(), "the exact current envelope reaches the real binding queue");
        assertEquals(retained.bytes(), rig.node.reserved());
        assertEquals(BindingDriver.QueueResult.QUEUED,
                rig.binding.queueOrdinary(retained, (int) retained.bytes()));
        invokeTeardown(rig.join);
        invokeTeardown(rig.join);
        assertEquals(0, rig.node.reserved(), "V3 teardown releases the queued unit exactly once");
        rig.close();
    }

    @Test
    void realFilterRejectsMissingGameEnvelopeAndAbortsTheCoreOwner() throws Exception {
        FilterRig rig = new FilterRig(EncoderMode.OUTPUT, false);
        ChannelPromise promise = rig.channel.newPromise();
        rig.channel.write(new ClientboundBlockChangedAckPacket(0), promise);
        assertTrue(promise.isDone() && !promise.isSuccess());
        assertEquals(null, rig.encoder.seenPromise, "missing envelope is refused before encoder invocation");
        assertEquals(1, rig.aborts.get());
        assertTrue(rig.core.driver.isClosed());
        assertEquals(0, rig.core.pool.reserved(rig.core.owner));
        rig.close();
    }

    @Test
    void activeRealSinkReleasesOrphanByteBufAndFailsTheJoin() throws Exception {
        FilterRig rig = new FilterRig(EncoderMode.OUTPUT, true);
        ByteBuf orphan = Unpooled.buffer().writeByte(7);
        ChannelPromise promise = rig.channel.newPromise();
        rig.channel.write(orphan, promise);
        assertEquals(0, orphan.refCnt(), "EncoderSink releases an active orphan buffer");
        assertTrue(promise.isDone() && !promise.isSuccess());
        assertEquals(null, rig.encoder.seenPromise, "orphan bytes never invoke the packet encoder");
        assertEquals(1, rig.aborts.get());
        assertTrue(rig.core.driver.isClosed());
        rig.close();
    }

    @Test
    void coveredExternalEncoderRefusalFailsOriginalPromiseAndSettlesCoreState() throws Exception {
        FilterRig rig = new FilterRig(EncoderMode.REFUSE, true);
        MultistreamSession.CausalScope scope = rig.core.driver.openScope();
        MultistreamSession.UpdateToken token = rig.core.driver.registerUpdate(scope);
        CausalSendEnvelope envelope = rig.context.newEnvelope(null,
                new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(token), null, null),
                CausalSendEnvelope.BundleMetadata.NONE);
        ChannelPromise promise = rig.channel.newPromise();
        CausalSendContext.Frame frame = rig.context.enter(envelope);
        try { rig.channel.write(new ClientboundBlockChangedAckPacket(0), promise); }
        finally { rig.context.exit(frame); }
        assertSame(promise, rig.encoder.seenPromise);
        assertTrue(promise.isDone() && !promise.isSuccess());
        assertEquals(1, rig.aborts.get());
        assertTrue(rig.core.driver.isClosed());
        assertEquals(0, rig.core.driver.trackerRetainedTokens());
        assertEquals(0, rig.core.pool.reserved(rig.core.owner));
        assertEquals(0, rig.covered.get());
        assertEquals(0, rig.ordinary.get());
        rig.close();
    }

    @Test
    void coveredZeroOutputFromRealEncoderFailsClosedAndSettlesToken() throws Exception {
        FilterRig rig = new FilterRig(EncoderMode.EMPTY, true);
        MultistreamSession.CausalScope scope = rig.core.driver.openScope();
        MultistreamSession.UpdateToken token = rig.core.driver.registerUpdate(scope);
        CausalSendEnvelope envelope = rig.context.newEnvelope(null,
                new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(token), null, null),
                CausalSendEnvelope.BundleMetadata.NONE);
        ChannelPromise promise = rig.channel.newPromise();
        CausalSendContext.Frame frame = rig.context.enter(envelope);
        try { rig.channel.write(new ClientboundBlockChangedAckPacket(0), promise); }
        finally { rig.context.exit(frame); }
        assertTrue(promise.isDone());
        assertSame(promise, rig.encoder.seenPromise);
        assertEquals(1, rig.aborts.get());
        assertEquals(0, rig.core.driver.trackerRetainedTokens());
        assertEquals(0, rig.core.pool.reserved(rig.core.owner));
        rig.close();
    }

    @Test
    void actualEmptyReplayWritesBytesBeforeCoreBindingAndCoveredReplayFailsClosed() throws Exception {
        FilterRig empty = new FilterRig(EncoderMode.OUTPUT, false, false);
        CausalSendEnvelope ordinary = empty.context.newEnvelope(null, CausalSendEnvelope.ProducerSnapshot.EMPTY,
                CausalSendEnvelope.BundleMetadata.NONE);
        CausalSendContext.Frame frame = empty.context.enter(ordinary);
        ChannelPromise promise = empty.channel.newPromise();
        try { empty.channel.write(new ClientboundBlockChangedAckPacket(0), promise); }
        finally { empty.context.exit(frame); }
        empty.channel.flush();
        assertTrue(promise.isSuccess());
        ByteBuf replayed = empty.channel.readOutbound();
        assertEquals(2, replayed.readableBytes());
        assertEquals(1, replayed.readUnsignedByte());
        assertEquals(0x25, replayed.readUnsignedByte());
        replayed.release();

        empty.attachedCore.set(empty.coreSession);
        empty.context.bindSession(empty.transport, empty.coreSession);
        CausalSendEnvelope afterBind = empty.context.newEnvelope(null, CausalSendEnvelope.ProducerSnapshot.EMPTY,
                CausalSendEnvelope.BundleMetadata.NONE);
        CausalSendContext.Frame afterBindFrame = empty.context.enter(afterBind);
        ChannelPromise afterBindPromise = empty.channel.newPromise();
        try { empty.channel.write(new ClientboundBlockChangedAckPacket(0), afterBindPromise); }
        finally { empty.context.exit(afterBindFrame); }
        empty.channel.flush();
        assertTrue(afterBindPromise.isDone());
        assertEquals(1, empty.aborts.get());
        assertTrue(empty.core.driver.isClosed());
        assertNull(empty.channel.readOutbound(),
                "byte replay refuses even empty coverage after core session binding");
        empty.close();

        FilterRig covered = new FilterRig(EncoderMode.OUTPUT, false, false);
        MultistreamSession.CausalScope scope = covered.core.driver.openScope();
        MultistreamSession.UpdateToken token = covered.core.driver.registerUpdate(scope);
        CausalSendEnvelope coveredEnvelope = covered.context.newEnvelope(null,
                new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(token), null, null),
                CausalSendEnvelope.BundleMetadata.NONE);
        CausalSendContext.Frame coveredFrame = covered.context.enter(coveredEnvelope);
        ChannelPromise coveredPromise = covered.channel.newPromise();
        try { covered.channel.write(new ClientboundBlockChangedAckPacket(0), coveredPromise); }
        finally { covered.context.exit(coveredFrame); }
        covered.channel.flush();
        assertTrue(coveredPromise.isDone());
        assertEquals(1, covered.aborts.get());
        assertEquals(0, covered.core.driver.trackerRetainedTokens());
        assertEquals(0, covered.core.pool.reserved(covered.core.owner));
        assertNull(covered.channel.readOutbound(), "covered replay is refused before any byte reaches the channel");
        covered.close();
    }

    @Test
    void reentrantInvalidationOnFirstRealOrdinarySendAbortsWithoutSubmittingSecondMember() throws Exception {
        FilterRig rig = new FilterRig(EncoderMode.OUTPUT, true);
        rig.afterOrdinary.set(rig.context::teardown);
        OutboundUnit split = new OutboundUnit(false, rig.context.newEnvelope(null,
                CausalSendEnvelope.ProducerSnapshot.EMPTY, CausalSendEnvelope.BundleMetadata.NONE));
        split.add(ProtocolState.PLAY, new byte[] {1, 0x25});
        split.add(ProtocolState.PLAY, new byte[] {1, 0x25});
        Object sender = inner(rig.join, "LaneSender");
        Method send = sender.getClass().getDeclaredMethod("send", OutboundUnit.class);
        send.setAccessible(true);
        try { send.invoke(sender, split); throw new AssertionError("terminal admission must fail the lane send"); }
        catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
        assertEquals(1, rig.ordinary.get(), "the first core send commits; later members are not attempted");
        assertEquals(0, rig.covered.get());
        assertEquals(1, rig.aborts.get(), "LaneSender failure reaches the actual join abort path");
        assertTrue(rig.context.currentEnvelope() == null);
        assertTrue(rig.core.driver.isClosed());
        assertEquals(0, rig.core.driver.queuedRecords());
        assertEquals(0, rig.core.pool.reserved(rig.core.owner));
        rig.close();
    }

    private static MultistreamSession driverAdapter(SessionSendDriver driver, AtomicInteger covered,
            AtomicInteger ordinary) {
        return driverAdapter(driver, covered, ordinary, () -> () -> { });
    }

    private static MultistreamSession driverAdapter(SessionSendDriver driver, AtomicInteger covered,
            AtomicInteger ordinary, java.util.function.Supplier<Runnable> afterOrdinary) {
        return (MultistreamSession) Proxy.newProxyInstance(MultistreamSession.class.getClassLoader(),
                new Class<?>[] {MultistreamSession.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "openScope": return driver.openScope();
                    case "registerUpdate": return driver.registerUpdate((MultistreamSession.CausalScope) args[0]);
                    case "completeScope": driver.completeScope((MultistreamSession.CausalScope) args[0]); return null;
                    case "sendCovered":
                        covered.incrementAndGet();
                        if (args[1] != ProtocolState.PLAY || ((Integer) args[2]) != (args[4] == null ? -1
                                : rs.sudoe.quicraft.fabric.net.FrameCodec.packetId((byte[]) args[4]))) {
                            throw new IllegalArgumentException("state or packet id metadata mismatch");
                        }
                        return driver.submitCovered((MultistreamSession.UpdateToken) args[0],
                                new OutboundPacket((byte[]) args[3]), StreamSequencer.Fence.ORDINARY_CONTROL,
                                false, 0, 0);
                    case "send":
                        ordinary.incrementAndGet();
                        SentPacket assigned = driver.submit(new OutboundPacket((byte[]) args[2]),
                                StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
                        afterOrdinary.get().run();
                        return assigned;
                    default: return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static void invokeSubmit(V3Join join, OutboundUnit unit) throws Exception {
        Method method = V3Join.class.getDeclaredMethod("submitUnit", OutboundUnit.class);
        method.setAccessible(true);
        try {
            method.invoke(join, unit);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            if (e.getCause() instanceof Error) throw (Error) e.getCause();
            throw e;
        }
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = V3Join.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static OutboundUnit coveredUnit(CausalSendContext context, MultistreamSession.UpdateToken token) {
        OutboundUnit unit = new OutboundUnit(false, context.newEnvelope(null,
                new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(token), null, null),
                CausalSendEnvelope.BundleMetadata.NONE));
        unit.add(ProtocolState.PLAY, new byte[] {1, 0x25});
        return unit;
    }

    private static OutboundUnit coveredUnit(SubmissionRig rig, List<MultistreamSession.UpdateToken> tokens,
            List<MultistreamSession.CausalScope> scopes, CausalSendEnvelope.AckOperation ack,
            CausalSendEnvelope.BatchOperation batch, boolean bundle, int... packetSizes) {
        OutboundUnit unit = new OutboundUnit(bundle, rig.context.newEnvelope(null,
                new CausalSendEnvelope.ProducerSnapshot(scopes, tokens, ack, batch),
                bundle ? CausalSendEnvelope.BundleMetadata.of(List.of()) : CausalSendEnvelope.BundleMetadata.NONE));
        for (int size : packetSizes) {
            unit.add(ProtocolState.PLAY, new byte[size]);
        }
        return unit;
    }

    private static Object inner(V3Join join, String name, Object... arguments) throws Exception {
        Class<?> type = Class.forName(V3Join.class.getName() + "$" + name);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == arguments.length + 1) {
                constructor.setAccessible(true);
                Object[] actual = new Object[arguments.length + 1];
                actual[0] = join;
                System.arraycopy(arguments, 0, actual, 1, arguments.length);
                return constructor.newInstance(actual);
            }
        }
        throw new NoSuchMethodException("no inner constructor for " + name);
    }

    private static void invokeTeardown(V3Join join) throws Exception {
        Method method = V3Join.class.getDeclaredMethod("teardown");
        method.setAccessible(true);
        try { method.invoke(join); }
        catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            if (e.getCause() instanceof Error) throw (Error) e.getCause();
            throw e;
        }
    }
}
