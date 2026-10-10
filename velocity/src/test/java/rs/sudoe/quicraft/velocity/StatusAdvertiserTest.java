// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.protocol.packet.StatusResponsePacket;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

class StatusAdvertiserTest {
    private static final Logger LOG = LoggerFactory.getLogger(StatusAdvertiserTest.class);
    private static final String STATUS = "{\"version\":{\"name\":\"Velocity 3.x-26.1\",\"protocol\":775},"
            + "\"players\":{\"max\":500,\"online\":0},\"description\":{\"text\":\"A Velocity Server\"}}";

    private static Advertisement ad() throws Exception {
        return Advertisement.v1(25577, ServerIdentity.generate().fingerprint());
    }

    /** Stands in for Velocity's minecraft-encoder: records packet objects as they arrive. */
    @ChannelHandler.Sharable
    private static final class Recorder extends ChannelOutboundHandlerAdapter {
        final List<Object> written = new ArrayList<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) {
            written.add(msg);
            promise.setSuccess();
        }
    }

    /** Like Velocity's ServerChannelInitializer: names the handlers the same way. */
    @ChannelHandler.Sharable
    private static final class FakeVelocityInitializer extends ChannelInitializer<Channel> {
        final Recorder encoder = new Recorder();

        @Override
        protected void initChannel(Channel ch) {
            ch.pipeline().addLast("frame-encoder", new ChannelOutboundHandlerAdapter());
            ch.pipeline().addLast("minecraft-encoder", encoder);
            ch.pipeline().addLast("handler", new ChannelOutboundHandlerAdapter());
        }
    }

    private static String send(StatusAdvertiser advertiser, String json) {
        FakeVelocityInitializer velocity = new FakeVelocityInitializer();
        EmbeddedChannel ch = new EmbeddedChannel(new AdvertisingInitializer(velocity, advertiser, LOG));
        ch.writeAndFlush(new StatusResponsePacket(json));
        ch.finishAndReleaseAll();
        assertEquals(1, velocity.encoder.written.size());
        return ((StatusResponsePacket) velocity.encoder.written.get(0)).getStatus();
    }

    @Test
    void theAdvertiserSitsRightAfterTheEncoderAndVelocitysPipelineIsOtherwiseUnchanged() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(
                new AdvertisingInitializer(new FakeVelocityInitializer(), new StatusAdvertiser(ad(), LOG), LOG));
        assertEquals(List.of("frame-encoder", "minecraft-encoder", StatusAdvertiser.NAME, "handler"),
                ch.pipeline().names().subList(0, 4));
        ch.finishAndReleaseAll();
    }

    @Test
    void statusResponsesCarryTheAdvertisement() throws Exception {
        Advertisement ad = ad();
        String sent = send(new StatusAdvertiser(ad, LOG), STATUS);
        assertEquals(Optional.of(ad), Advertisement.extract(sent));
        assertTrue(sent.startsWith(STATUS.substring(0, STATUS.length() - 1)), "Velocity's own fields stay as they were");
    }

    @Test
    void otherPacketsPassThroughUntouched() throws Exception {
        FakeVelocityInitializer velocity = new FakeVelocityInitializer();
        EmbeddedChannel ch = new EmbeddedChannel(
                new AdvertisingInitializer(velocity, new StatusAdvertiser(ad(), LOG), LOG));
        Object other = new Object();
        ch.writeAndFlush(other);
        ch.finishAndReleaseAll();
        assertSame(other, velocity.encoder.written.get(0));
    }

    /** Phase 2 gate: a maximum-size MOTD and favicon still ping; the advertisement gives way. */
    @Test
    void aResponseNearTheLengthCapIsSentUnchangedAndStaysUnderTheCap() throws Exception {
        String favicon = "data:image/png;base64," + "A".repeat(20_000);
        String head = "{\"version\":{\"name\":\"Velocity\",\"protocol\":775},\"players\":{\"max\":1,\"online\":0},"
                + "\"favicon\":\"" + favicon + "\",\"description\":{\"text\":\"";
        String tail = "\"}}";
        String full = head + "m".repeat(Advertisement.STATUS_MAX_LENGTH - head.length() - tail.length()) + tail;
        assertEquals(Advertisement.STATUS_MAX_LENGTH, full.length());
        assertEquals(full, send(new StatusAdvertiser(ad(), LOG), full));

        // Just enough room: the advertisement goes in and the result is still within the cap.
        Advertisement ad = ad();
        int room = Advertisement.STATUS_MAX_LENGTH - (1 + Protocol.ADVERTISEMENT_KEY.length() + 3 + ad.toJson().length());
        String fits = head + "m".repeat(room - head.length() - tail.length()) + tail;
        String sent = send(new StatusAdvertiser(ad, LOG), fits);
        assertEquals(Optional.of(ad), Advertisement.extract(sent));
        assertEquals(Advertisement.STATUS_MAX_LENGTH, sent.length());
    }

    @Test
    void anythingUnexpectedGoesOutUnchanged() throws Exception {
        StatusAdvertiser advertiser = new StatusAdvertiser(ad(), LOG);
        assertEquals("not json", send(advertiser, "not json"));
        assertEquals("[1,2]", send(advertiser, "[1,2]"));
        // A null status makes getStatus() throw inside the handler; the packet still goes out.
        FakeVelocityInitializer velocity = new FakeVelocityInitializer();
        EmbeddedChannel ch = new EmbeddedChannel(new AdvertisingInitializer(velocity, advertiser, LOG));
        StatusResponsePacket broken = new StatusResponsePacket();
        ch.writeAndFlush(broken);
        ch.finishAndReleaseAll();
        assertSame(broken, velocity.encoder.written.get(0));
    }

    @Test
    void aWithdrawnAdvertisementIsNoLongerSent() throws Exception {
        StatusAdvertiser advertiser = new StatusAdvertiser(ad(), LOG);
        advertiser.withdraw();
        assertEquals(STATUS, send(advertiser, STATUS));
    }

    @Test
    void withoutAMinecraftEncoderNothingIsAdded() throws Exception {
        ChannelInitializer<Channel> other = new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.pipeline().addLast("something-else", new ChannelOutboundHandlerAdapter());
            }
        };
        EmbeddedChannel ch = new EmbeddedChannel(new AdvertisingInitializer(other, new StatusAdvertiser(ad(), LOG), LOG));
        assertNull(ch.pipeline().get(StatusAdvertiser.NAME));
        assertNotNull(ch.pipeline().get("something-else"));
        ch.finishAndReleaseAll();
    }
}
