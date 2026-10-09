// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.quic.QuicTokenHandler;
import java.net.InetSocketAddress;

/**
 * Never sends Retry and accepts no tokens, the same as Netty's package-private
 * {@code NoQuicTokenHandler}. A real address-validation handler replaces it before the public
 * alpha (Phase 4, docs/protocol.md §9). Never Netty's insecure example handler.
 */
final class NoRetryTokenHandler implements QuicTokenHandler {
    static final NoRetryTokenHandler INSTANCE = new NoRetryTokenHandler();

    private NoRetryTokenHandler() {}

    @Override
    public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
        return false;
    }

    @Override
    public int validateToken(ByteBuf token, InetSocketAddress address) {
        return -1;
    }

    @Override
    public int maxTokenLength() {
        return 0;
    }
}
