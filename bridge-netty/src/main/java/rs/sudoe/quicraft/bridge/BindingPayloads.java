// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.binding.BindingDriver;

/**
 * The glue of docs/protocol.md 12.8 between a game platform and one connection's
 * {@link BindingDriver}: it carries the driver's {@link BindingDriver.Callbacks} over the
 * platform's own "send one {@code quicraft:binding} custom payload" function, and feeds the payload
 * bytes the platform receives back into {@link BindingDriver#onPayload(byte[])}.
 *
 * <p>Everything else the driver asks for (entering configuration, the tick, the quiescence queue of
 * 12.10) is forwarded unchanged, so the platform talks to one object for the whole exchange.</p>
 *
 * <p><b>A connection that is not {@code /3} has no driver.</b> {@link #outsideV3} builds the same
 * object around nothing: a {@code quicraft:binding} payload that arrives there (a {@code /2}
 * connection an ordinary plugin sent it on, a peer probing) triggers no binding, pin, cipher,
 * framing or transport action at all, and is logged at {@code FINE} once, which is exactly what
 * 12.8 requires. The quiescence calls then answer for a connection that is not pausing anything:
 * {@link #queueOrdinary(Object, int)} sends the packet now and nothing is ever queued.</p>
 *
 * <p>Threading: the same as {@link BindingDriver}: the platform calls {@link #onPayload(byte[])} on
 * the connection's event loop, and every callback arrives there.</p>
 */
public final class BindingPayloads implements BindingDriver.Callbacks {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    /** Sends one {@code quicraft:binding} configuration custom payload verbatim (12.8). */
    public interface PayloadSender {
        /**
         * @param payload the 12.8 payload, exactly as the driver produced it; the platform puts it
         *     on the wire as a configuration Custom Payload packet on {@code quicraft:binding} and
         *     never hands it to an ordinary plugin
         */
        void sendBindingPayload(byte[] payload);
    }

    /** The driver of this connection, or null when it is not {@code /3}. */
    private final BindingDriver driver;
    /** The platform's own handling of the cutover and of failures. */
    private final BindingDriver.Callbacks platform;
    private final PayloadSender sender;

    /**
     * @param driver the connection's binding driver; null for a connection that is not {@code /3}
     * @param platform what the cutover callbacks of 12.11/12.12 and {@link Callbacks#fail} do on
     *     this platform; required
     * @param sender how a binding payload reaches the wire on this platform; required
     */
    public BindingPayloads(BindingDriver driver, BindingDriver.Callbacks platform, PayloadSender sender) {
        if (platform == null || sender == null) {
            throw new IllegalArgumentException("platform and sender are required");
        }
        this.driver = driver;
        this.platform = platform;
        this.sender = sender;
    }

    /** The glue for a connection that is not {@code /3}: no driver, so a payload does nothing (12.8). */
    public static BindingPayloads outsideV3(BindingDriver.Callbacks platform, PayloadSender sender) {
        return new BindingPayloads(null, platform, sender);
    }

    /** True when this connection runs the {@code /3} binding exchange. */
    public boolean isV3() {
        return driver != null;
    }

    /**
     * One received {@code quicraft:binding} payload, from the platform's configuration-stage packet
     * handling. Without a driver this is a debug line and nothing else (12.8).
     */
    public void onPayload(byte[] payload) {
        BindingDriver current = driver;
        if (current == null) {
            LOG.log(Level.FINE, "a quicraft:binding payload arrived on a connection that is not /3"
                    + " (12.8); ignoring it");
            return;
        }
        current.onPayload(payload);
    }

    /** The exchange begins (12.4); forwarded to the driver, nothing without one. */
    public void onConfigurationEntered(long nowNanos) {
        if (driver != null) {
            driver.onConfigurationEntered(nowNanos);
        }
    }

    /** Advances the driver's deadlines (12.14); nothing without a driver. */
    public void tick(long nowNanos) {
        if (driver != null) {
            driver.tick(nowNanos);
        }
    }

    /**
     * Offers one ordinary outbound packet to the driver's quiescence queue (12.10). Without a
     * driver the connection is not pausing anything, so the answer is always
     * {@link QueueResult#SEND_NOW}.
     */
    public BindingDriver.QueueResult queueOrdinary(Object packet, int bytes) {
        if (driver == null) {
            return BindingDriver.QueueResult.SEND_NOW;
        }
        return driver.queueOrdinary(packet, bytes);
    }

    /** The packets the driver queued during the exchange (12.10); empty without a driver. */
    public List<Object> releaseQueued() {
        return driver == null ? Collections.emptyList() : driver.releaseQueued();
    }

    // --- BindingDriver.Callbacks: what the driver asks this platform to do.

    @Override
    public void send(byte[] payload) {
        sender.sendBindingPayload(payload);
    }

    @Override
    public void activateOutbound() {
        platform.activateOutbound();
    }

    @Override
    public void activateInbound() {
        platform.activateInbound();
    }

    @Override
    public void fail(int code, String reason) {
        platform.fail(code, reason);
    }

    /** The driver this object carries, for the platform's own diagnostics, or null. */
    public BindingDriver driver() {
        return driver;
    }
}
