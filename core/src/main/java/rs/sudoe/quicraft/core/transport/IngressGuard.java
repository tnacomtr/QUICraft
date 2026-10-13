// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.quic.QuicHeaderParser;
import io.netty.handler.codec.quic.QuicPacketType;
import java.net.InetSocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Packet-flood guard in front of the listener's QUIC codec (docs/protocol.md §9). Drops a datagram
 * that starts with a long-header Initial but is shorter than 1200 bytes (RFC 9000 §14.1), so no
 * reply, Retry included, answers a small datagram. Counts every other such Initial against its own
 * datagram-level {@link ConnectionAdmission} and drops the ones over budget. Every other datagram, and any datagram it fails to classify, goes to the codec
 * unchanged. One instance per listener; runs on the listener's event loop.
 */
final class IngressGuard extends ChannelInboundHandlerAdapter {
    private static final Logger LOG = Logger.getLogger("QUICraft");
    /** RFC 9000 §14.1: the smallest datagram a client may carry an Initial in. */
    static final int MIN_INITIAL_DATAGRAM = 1200;

    private final ConnectionAdmission ingress;
    private final int localConnectionIdLength;
    private QuicHeaderParser parser;
    /** Set by {@link #classify}; read right after the parse that set it, on the same thread. */
    private boolean initial;
    private final QuicHeaderParser.QuicHeaderProcessor classify =
            (sender, recipient, packet, type, version, scid, dcid, token) -> initial = type == QuicPacketType.INITIAL;

    IngressGuard(ConnectionAdmission ingress, int localConnectionIdLength) {
        this.ingress = ingress;
        this.localConnectionIdLength = localConnectionIdLength;
    }

    static IngressGuard forConfig(TransportConfig config) {
        return new IngressGuard(new ConnectionAdmission(
                config.ingressInitialsPerSecond,
                config.ingressInitialsPerIpPerSecond,
                1, 1, // open leases: unused here
                config.ingressInitialsPerSecond, // Retry threshold: unused here
                config.maxTrackedIps), Codecs.LOCAL_CONNECTION_ID_LENGTH);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        parser = new QuicHeaderParser(localConnectionIdLength);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (parser != null) {
            parser.close();
            parser = null;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        boolean drop = false;
        try {
            drop = msg instanceof DatagramPacket && shouldDrop((DatagramPacket) msg);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "QUIC ingress guard failed; passing the datagram on", t);
        }
        if (drop) {
            ((DatagramPacket) msg).release();
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private boolean shouldDrop(DatagramPacket packet) {
        InetSocketAddress sender = packet.sender();
        if (parser == null || sender == null || sender.getAddress() == null) {
            return false;
        }
        initial = false;
        try {
            // The parser reads from absolute offset 0; a slice leaves the packet's indices alone.
            parser.parse(sender, packet.recipient(), packet.content().slice(), classify);
        } catch (Exception notQuic) {
            return false;
        }
        if (!initial) {
            return false;
        }
        // Too short: dropped before the budget, so it spends none.
        return packet.content().readableBytes() < MIN_INITIAL_DATAGRAM || !ingress.allowAttempt(sender.getAddress());
    }
}
