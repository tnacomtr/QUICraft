// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The decoder's finally-scoped context: gates decide per connection at decode time, never earlier. */
class BindingDecodeContextTest {
    @AfterEach
    void clean() {
        BindingDecodeContext.enter(null);
    }

    @Test
    void anUnenteredThreadHasNoContextAndExpectsNoBinding() {
        assertNull(BindingDecodeContext.current());
        assertFalse(BindingDecodeContext.bindingExpected());
    }

    @Test
    void anEnteredChannelWithoutAnyGateExpectsNoBinding() {
        EmbeddedChannel channel = new EmbeddedChannel();
        BindingDecodeContext.enter(channel);
        assertSame(channel, BindingDecodeContext.current());
        assertFalse(BindingDecodeContext.bindingExpected(), "no pipeline gate and no server gate");
        BindingDecodeContext.enter(null);
    }

    @Test
    void aPipelineGateThatExpectsBindingSelectsTheSharedCodec() {
        EmbeddedChannel channel = new EmbeddedChannel(new Gate(true));
        BindingDecodeContext.enter(channel);
        assertSame(channel, BindingDecodeContext.current());
        assertTrue(BindingDecodeContext.bindingExpected(), "the pipeline gate alone");
        BindingDecodeContext.enter(null);
    }

    @Test
    void theServerAttributeGateSelectsTheSharedCodecWithoutAPipelineHandler() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(BindingDecodeContext.SERVER_GATE).set(() -> true);
        BindingDecodeContext.enter(channel);
        assertTrue(BindingDecodeContext.bindingExpected(), "the published server gate alone");
        BindingDecodeContext.enter(null);
    }

    @Test
    void theServerAndPipelineGatesCombineAsOr() {
        EmbeddedChannel serverOnly = new EmbeddedChannel(new Gate(false));
        serverOnly.attr(BindingDecodeContext.SERVER_GATE).set(() -> true);
        BindingDecodeContext.enter(serverOnly);
        assertTrue(BindingDecodeContext.bindingExpected(), "server gate on, pipeline gate off");
        BindingDecodeContext.enter(null);

        EmbeddedChannel pipelineOnly = new EmbeddedChannel(new Gate(true));
        BindingDecodeContext.enter(pipelineOnly);
        assertTrue(BindingDecodeContext.bindingExpected(), "pipeline gate on (no server gate at all)");
        BindingDecodeContext.enter(null);
    }

    @Test
    void aDifferentChannelNeverLeaksItsGate() {
        EmbeddedChannel expecting = new EmbeddedChannel(new Gate(true));
        EmbeddedChannel quiet = new EmbeddedChannel();
        expecting.attr(BindingDecodeContext.SERVER_GATE).set(() -> true);
        BindingDecodeContext.enter(expecting);
        assertTrue(BindingDecodeContext.bindingExpected());
        BindingDecodeContext.enter(quiet);
        assertFalse(BindingDecodeContext.bindingExpected(),
                "the channel switch: the expecting channel's gates must not leak into this decode");
        BindingDecodeContext.enter(null);
    }

    @Test
    void enteringNullRemovesTheCurrentChannelAndTheSelection() {
        EmbeddedChannel channel = new EmbeddedChannel(new Gate(true));
        BindingDecodeContext.enter(channel);
        assertSame(channel, BindingDecodeContext.current());
        assertTrue(BindingDecodeContext.bindingExpected());
        BindingDecodeContext.enter(null);
        assertNull(BindingDecodeContext.current());
        assertFalse(BindingDecodeContext.bindingExpected(), "the decode left the thread with no selection");
        BindingDecodeContext.enter(channel);
        assertTrue(BindingDecodeContext.bindingExpected(), "the same channel re-entered selects again");
        BindingDecodeContext.enter(null);
    }

    @Test
    void aMutableGateIsReevaluatedOnEveryDecode() {
        MutableGate gate = new MutableGate();
        EmbeddedChannel channel = new EmbeddedChannel(gate);
        BindingDecodeContext.enter(channel);
        assertFalse(BindingDecodeContext.bindingExpected(), "starts off");
        gate.on = true;
        assertTrue(BindingDecodeContext.bindingExpected(), "the value is re-read, never cached at enter time");
        gate.on = false;
        assertFalse(BindingDecodeContext.bindingExpected());
        BindingDecodeContext.enter(null);
    }

    @Test
    void theContextDoesNotTravelAcrossThreads() throws InterruptedException {
        EmbeddedChannel main = new EmbeddedChannel(new Gate(true));
        BindingDecodeContext.enter(main);
        boolean[] otherSawExchange = {true};
        Object[] otherCurrent = {"untouched"};
        boolean[] otherKeptItsOwn = {false};
        Thread other = new Thread(() -> {
            otherSawExchange[0] = BindingDecodeContext.bindingExpected();
            otherCurrent[0] = BindingDecodeContext.current();
            EmbeddedChannel own = new EmbeddedChannel(new Gate(true));
            BindingDecodeContext.enter(own);
            otherKeptItsOwn[0] = BindingDecodeContext.current() == own;
            BindingDecodeContext.enter(null);
            own.finishAndReleaseAll();
        });
        other.start();
        other.join(5000);
        assertFalse(other.isAlive(), "the decoding thread ran to completion");
        assertFalse(otherSawExchange[0], "the other thread did not inherit the entered channel");
        assertNull(otherCurrent[0], "and saw no current channel at all");
        assertTrue(otherKeptItsOwn[0], "it still obtained its own selection while this one was entered");
        assertSame(main, BindingDecodeContext.current(), "the main thread's entered channel is untouched");
        assertTrue(BindingDecodeContext.bindingExpected(), "and its gate is still expected");
        BindingDecodeContext.enter(null);
    }

    /** A stand-in pipeline handler whose gate answer is fixed at construction. */
    private static final class Gate extends ChannelInboundHandlerAdapter implements BindingDecodeContext.Gate {
        private final boolean value;

        Gate(boolean value) {
            this.value = value;
        }

        @Override public boolean expectsBinding() { return value; }
    }

    /** A stand-in gate mutated between calls: the context must consult it again and again. */
    private static final class MutableGate extends ChannelInboundHandlerAdapter implements BindingDecodeContext.Gate {
        private boolean on;

        @Override public boolean expectsBinding() { return on; }
    }
}
