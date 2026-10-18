// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.CausalSendContext;
import rs.sudoe.quicraft.fabric.server.CausalSendEnvelope;

/**
 * U3-W2a: one immutable causal envelope per logical server-bound send, carried explicitly through
 * the two observed scheduling boundaries of Minecraft 26.1.2's {@code Connection}:
 *
 * <ul>
 *   <li>{@code send(Packet, ChannelFutureListener, boolean)} inserts a pending task into
 *       {@code pendingActions} when the channel is not connected yet (source line 283): the
 *       {@code Queue.add} operation is wrapped so the task object itself holds the envelope.</li>
 *   <li>{@code sendPacket(Packet, ChannelFutureListener, boolean)} hands a runnable to
 *       {@code channel.eventLoop().execute} when the send is off the loop (source line 301): that
 *       runnable is wrapped so the envelope travels with it.</li>
 * </ul>
 *
 * <p>The arity-3 {@code send} is also the only scheduling method: the arity-1 and arity-2 overloads
 * delegate to it synchronously (source lines 270 to 285), so wrapping it once gives exactly one
 * envelope per public send and repeated sends of the same packet object get distinct identities.
 * {@code runOnceConnected}, {@code flushChannel}, {@code flush} and {@code flushQueue} are not
 * touched; their consumers, tasks and queue order stay vanilla. The frame is snapshotted at the
 * public entry but is only made visible to the write side around that send's own
 * {@code sendPacket} call, so a {@code runOnceConnected} consumer that runs during the preceding
 * queue drain cannot inherit it. The inline dispatch path needs no runnable: the marked frame is
 * current for the vanilla {@code sendPacket} call, so the write reads it through
 * {@link CausalSendContext#currentEnvelope(io.netty.channel.Channel)}.
 *
 * <p>Every hook is fail safe. A project failure falls back to the original task or runnable and the
 * vanilla send happens exactly once; the TCP path never depends on this class.
 */
@Mixin(Connection.class)
abstract class CausalConnectionMixin {
    private static final String SEND = "send(Lnet/minecraft/network/protocol/Packet;"
            + "Lio/netty/channel/ChannelFutureListener;Z)V";
    private static final String SEND_PACKET = "sendPacket(Lnet/minecraft/network/protocol/Packet;"
            + "Lio/netty/channel/ChannelFutureListener;Z)V";
    private static final String QUEUE_ADD = "Ljava/util/Queue;add(Ljava/lang/Object;)Z";
    private static final String EVENT_LOOP_EXECUTE = "Lio/netty/channel/EventLoop;execute(Ljava/lang/Runnable;)V";
    private static final String CONNECTION_SEND_PACKET = "Lnet/minecraft/network/Connection;sendPacket("
            + "Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V";

    @Unique
    private volatile CausalSendContext quicraft$causalSendContext;

    @Unique
    private CausalSendContext quicraft$causalContext() {
        CausalSendContext context = this.quicraft$causalSendContext;
        if (context == null) {
            synchronized (this) {
                context = this.quicraft$causalSendContext;
                if (context == null) {
                    context = new CausalSendContext(this);
                    this.quicraft$causalSendContext = context;
                }
            }
        }
        return context;
    }

    /**
     * Snapshot the logical send before vanilla does anything, so the epoch and producer facts are
     * those at send time, and close the frame after the vanilla call whether it returns or throws.
     */
    @WrapMethod(method = SEND)
    private void quicraft$causalSend(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush,
            Operation<Void> original) {
        Connection self = (Connection) (Object) this;
        CausalSendContext.Frame frame = null;
        try {
            Hooks.enter("causal send");
            if (self.getSending() == PacketFlow.CLIENTBOUND) {
                CausalSendContext context = quicraft$causalContext();
                CausalSendEnvelope envelope = context.newEnvelope(self.getPacketListener(),
                        CausalSendEnvelope.ProducerSnapshot.EMPTY, quicraft$bundle(packet));
                frame = context.enter(envelope, false);
            }
        } catch (Throwable t) {
            Hooks.failed("causal send", t);
            frame = null;
        }
        try {
            original.call(packet, listener, flush);
        } finally {
            if (frame != null) {
                try {
                    quicraft$causalContext().exit(frame);
                } catch (Throwable t) {
                    Hooks.failed("causal send exit", t);
                }
            }
        }
    }

    /**
     * The connected path's own dispatch: mark the snapshotted send frame as dispatching for exactly
     * this vanilla {@code sendPacket} call, so an unrelated action that ran during the preceding
     * {@code flushQueue} could not have inherited it and the inline write can read it.
     */
    @WrapOperation(method = SEND, at = @At(value = "INVOKE", target = CONNECTION_SEND_PACKET))
    private void quicraft$markDispatch(Connection self, Packet<?> packet,
            @Nullable ChannelFutureListener listener, boolean flush, Operation<Void> original) {
        CausalSendContext.Frame frame = null;
        try {
            Hooks.enter("causal dispatch start");
            CausalSendContext context = quicraft$causalContext();
            frame = context.currentFrame();
            if (frame != null) {
                frame.dispatching(true);
            }
        } catch (Throwable t) {
            Hooks.failed("causal dispatch start", t);
            frame = null;
        }
        try {
            original.call(self, packet, listener, flush);
        } finally {
            if (frame != null) {
                try {
                    frame.dispatching(false);
                } catch (Throwable t) {
                    Hooks.failed("causal dispatch end", t);
                }
            }
        }
    }

    /**
     * The disconnected path of {@code send}: the vanilla lambda is the pending task. Replace only
     * that task object with the carrying wrapper; the queue, its order and its drain stay vanilla.
     */
    @WrapOperation(method = SEND, at = @At(value = "INVOKE", target = QUEUE_ADD))
    private boolean quicraft$carryPending(Queue<?> queue, Object task, Operation<Boolean> original) {
        Object toAdd = task;
        try {
            Hooks.enter("causal pending");
            CausalSendContext context = quicraft$causalContext();
            CausalSendContext.Frame frame = context.currentFrame();
            CausalSendEnvelope envelope = frame == null ? null : frame.envelope();
            if (envelope != null && task instanceof Consumer<?> consumer) {
                @SuppressWarnings("unchecked")
                Consumer<Connection> vanilla = (Consumer<Connection>) consumer;
                toAdd = context.carryTask(vanilla, envelope);
            }
        } catch (Throwable t) {
            Hooks.failed("causal pending", t);
            toAdd = task;
        }
        return original.call(queue, toAdd);
    }

    /**
     * The off-loop path of {@code sendPacket}: the vanilla lambda dispatches one write. Replace it
     * with the carrying wrapper so the envelope is current when the write happens on the loop.
     */
    @WrapOperation(method = SEND_PACKET, at = @At(value = "INVOKE", target = EVENT_LOOP_EXECUTE))
    private void quicraft$carryDispatch(EventLoop loop, Runnable task, Operation<Void> original) {
        Runnable toRun = task;
        try {
            Hooks.enter("causal dispatch");
            CausalSendContext context = quicraft$causalContext();
            CausalSendEnvelope envelope = context.currentEnvelope();
            if (envelope != null) {
                toRun = context.carryRunnable(task, envelope);
            }
        } catch (Throwable t) {
            Hooks.failed("causal dispatch", t);
            toRun = task;
        }
        original.call(loop, toRun);
    }

    /** Publish the context on the active channel: this is where the write side reads it. */
    @Inject(method = "channelActive(Lio/netty/channel/ChannelHandlerContext;)V", at = @At("HEAD"))
    private void quicraft$attachContext(ChannelHandlerContext ctx, CallbackInfo ci) {
        try {
            Hooks.enter("causal attach");
            if (((Connection) (Object) this).getSending() == PacketFlow.CLIENTBOUND) {
                CausalSendContext.attach(ctx.channel(), quicraft$causalContext());
            }
        } catch (Throwable t) {
            Hooks.failed("causal attach", t);
        }
    }

    /** Drop the retained frames when the channel ends; nothing of the send state outlives it. */
    @Inject(method = "channelInactive(Lio/netty/channel/ChannelHandlerContext;)V", at = @At("HEAD"))
    private void quicraft$teardown(ChannelHandlerContext ctx, CallbackInfo ci) {
        try {
            CausalSendContext context = this.quicraft$causalSendContext;
            if (context != null) {
                context.teardown();
            }
        } catch (Throwable t) {
            Hooks.failed("causal teardown", t);
        }
    }

    /** Read the bundle's ordered children once, here, and never again from the packet. */
    @Unique
    private static CausalSendEnvelope.BundleMetadata quicraft$bundle(Packet<?> packet) {
        if (!(packet instanceof BundlePacket<?> bundle)) {
            return CausalSendEnvelope.BundleMetadata.NONE;
        }
        if (!(bundle.subPackets() instanceof List<?> children)) {
            // A non-List Iterable is not guaranteed to be repeatable: vanilla must be the only
            // consumer, so the producer stage supplies those children instead of this snapshot.
            return CausalSendEnvelope.BundleMetadata.of(List.of());
        }
        List<CausalSendEnvelope.BundleChild> snapshot = new ArrayList<>(children.size());
        for (Object child : children) {
            if (!(child instanceof Packet<?> packetChild)) {
                return CausalSendEnvelope.BundleMetadata.of(List.of());
            }
            snapshot.add(new CausalSendEnvelope.BundleChild(snapshot.size(), packetChild));
        }
        return CausalSendEnvelope.BundleMetadata.of(snapshot);
    }
}
