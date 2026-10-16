// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Admission limits layered on the Retry token handler ({@link AdmissionTokenHandler},
 * docs/protocol.md §9). {@link #writeToken} and {@link #validateToken} are driven directly, with a
 * deterministic HMAC key and a frozen clock so neither the window nor the token lifetime can roll
 * over mid-test.
 *
 * <p>Token-less attempts spend from the {@code admission} budget; a valid token spends from the
 * separate {@code validated} budget and probes the open slot on {@code admission}. Those two
 * budgets are given different limits here so a swap would be visible.
 */
class AdmissionTokenHandlerTest {
    /** A clock frozen in time so no window and no token lifetime can roll over during a test. */
    private static final LongSupplier FROZEN = () -> 0L;

    /** A deterministic 32-byte HMAC key, so tokens validate without a random source. */
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < KEY.length; i++) {
            KEY[i] = (byte) i;
        }
    }

    private static final int DCID_LENGTH = 8;
    private static final int PREFIX_LENGTH = 41;

    private static final InetSocketAddress ADDRESS_1 = new InetSocketAddress("192.0.2.1", 5000);
    private static final InetSocketAddress ADDRESS_2 = new InetSocketAddress("192.0.2.2", 5000);
    private static final InetSocketAddress ADDRESS_3 = new InetSocketAddress("192.0.2.3", 5000);

    private final List<ByteBuf> ownedBuffers = new ArrayList<>();

    private static ConnectionAdmission admission(int global, int perIp, int maxOpen, int maxOpenPerIp,
            int retryThreshold) {
        return new ConnectionAdmission(global, perIp, maxOpen, maxOpenPerIp, retryThreshold, 100, FROZEN);
    }

    /** A handler with the deterministic key and frozen clock. */
    private static AdmissionTokenHandler handler(ConnectionAdmission admission, ConnectionAdmission validated,
            TransportConfig.RetryMode mode, AtomicLong retries) {
        return new AdmissionTokenHandler(admission, validated, mode, retries, false, KEY, FROZEN);
    }

    /** A {@code /3}-style handler (prefix-fair validated budget) with the deterministic key and frozen clock. */
    private static AdmissionTokenHandler fairHandler(ConnectionAdmission admission, ConnectionAdmission validated) {
        return new AdmissionTokenHandler(admission, validated, TransportConfig.RetryMode.NEVER, new AtomicLong(),
                true, KEY, FROZEN);
    }

    @Test
    void v2ValidatedBudgetKeepsThePlainLimitWhileV3IsPrefixFair() {
        // Two prefixes, a validated window of two attempts. The first prefix spends both.
        InetSocketAddress attacker1 = new InetSocketAddress("10.0.1.1", 5000);
        InetSocketAddress attacker2 = new InetSocketAddress("10.0.1.2", 5000);
        InetSocketAddress honest = new InetSocketAddress("10.0.2.1", 5000);

        AdmissionTokenHandler v2 = handler(admission(100, 100, 10, 10, 1), admission(2, 100, 10, 10, 2),
                TransportConfig.RetryMode.NEVER, new AtomicLong());
        assertTrue(v2.spendValidatedAttempt(attacker1.getAddress()));
        assertTrue(v2.spendValidatedAttempt(attacker2.getAddress()));
        assertFalse(v2.spendValidatedAttempt(honest.getAddress()), "/2: the plain limit refuses an honest prefix");

        AdmissionTokenHandler v3 = fairHandler(admission(100, 100, 10, 10, 1), admission(2, 100, 10, 10, 2));
        assertTrue(v3.spendValidatedAttempt(attacker1.getAddress()));
        assertTrue(v3.spendValidatedAttempt(attacker2.getAddress()));
        assertTrue(v3.spendValidatedAttempt(honest.getAddress()),
                "/3: an honest prefix within its share is admitted by taking from the over-share prefix");
    }

    private ByteBuf owned(ByteBuf buffer) {
        ownedBuffers.add(buffer);
        return buffer;
    }

    @AfterEach
    void releaseOwnedBuffers() {
        for (ByteBuf buffer : ownedBuffers) {
            if (buffer.refCnt() > 0) {
                buffer.release();
            }
        }
        ownedBuffers.clear();
    }

    private ByteBuf out() {
        return owned(Unpooled.buffer());
    }

    private ByteBuf dcid() {
        return owned(Unpooled.wrappedBuffer(new byte[DCID_LENGTH]));
    }

    /** Writes a token that must succeed, or fails the test. */
    private ByteBuf token(AdmissionTokenHandler handler, InetSocketAddress address) {
        ByteBuf out = out();
        assertTrue(handler.writeToken(out, dcid(), address), "the handler must write the token");
        return out;
    }

    // ── 8. ADAPTIVE below the threshold ──────────────────────────────────

    @Test
    void adaptiveBelowThresholdWritesNoTokenAndCountsNoRetry() {
        AtomicLong retries = new AtomicLong();
        ConnectionAdmission admission = admission(1000, 100, 100, 100, 50);
        AdmissionTokenHandler handler = handler(admission, admission(1000, 100, 100, 100, 50),
                TransportConfig.RetryMode.ADAPTIVE, retries);

        ByteBuf out = out();
        int writerIndex = out.writerIndex();

        assertFalse(handler.writeToken(out, dcid(), ADDRESS_1), "below the threshold no Retry is required");
        assertEquals(0, out.readableBytes(), "out stays empty");
        assertEquals(writerIndex, out.writerIndex(), "out is not written to");
        assertEquals(0, retries.get(), "no Retry was asked for");
    }

    // ── 9. ADAPTIVE at the threshold ─────────────────────────────────────

    @Test
    void adaptiveAtThresholdWritesTokenAndCountsRetry() {
        AtomicLong retries = new AtomicLong();
        ConnectionAdmission admission = admission(1000, 100, 100, 100, 1);
        AdmissionTokenHandler handler = handler(admission, admission(1000, 100, 100, 100, 1),
                TransportConfig.RetryMode.ADAPTIVE, retries);

        ByteBuf out = out();

        assertTrue(handler.writeToken(out, dcid(), ADDRESS_1), "at the threshold a Retry is required");
        assertEquals(PREFIX_LENGTH + DCID_LENGTH, out.readableBytes(), "a token was written");
        assertEquals(1, retries.get(), "the Retry counter incremented");
    }

    // ── 10. ALWAYS and NEVER ─────────────────────────────────────────────

    @Test
    void alwaysRetriesOnTheFirstAttempt() {
        AtomicLong retries = new AtomicLong();
        ConnectionAdmission admission = admission(1000, 100, 100, 100, 1000);
        AdmissionTokenHandler handler = handler(admission, admission(1000, 100, 100, 100, 1000),
                TransportConfig.RetryMode.ALWAYS, retries);

        assertTrue(handler.writeToken(out(), dcid(), ADDRESS_1), "ALWAYS retries the first attempt");
        assertEquals(1, retries.get());
    }

    @Test
    void neverWritesNoTokenWithinLimitsButForcesRetryOverThePerIpLimit() {
        AtomicLong retries = new AtomicLong();
        ConnectionAdmission admission = admission(1000, 1, 100, 100, 1000);
        AdmissionTokenHandler handler = handler(admission, admission(1000, 1, 100, 100, 1000),
                TransportConfig.RetryMode.NEVER, retries);

        ByteBuf first = out();
        assertFalse(handler.writeToken(first, dcid(), ADDRESS_1), "within limits NEVER writes no token");
        assertEquals(0, first.readableBytes());

        ByteBuf second = out();
        assertTrue(handler.writeToken(second, dcid(), ADDRESS_1), "a denied attempt forces a Retry whatever the mode");
        assertEquals(PREFIX_LENGTH + DCID_LENGTH, second.readableBytes());
        assertEquals(1, retries.get());

        assertFalse(handler.writeToken(out(), dcid(), ADDRESS_2), "another IP keeps its own attempt budget");
    }

    @Test
    void neverForcesRetryWhileHandshakesAreBacklogged() {
        AtomicLong retries = new AtomicLong();
        ConnectionAdmission admission = admission(1000, 100, 4, 4, 1000);
        AdmissionTokenHandler handler = handler(admission, admission(1000, 100, 4, 4, 1000),
                TransportConfig.RetryMode.NEVER, retries);

        ConnectionAdmission.Lease pending = admission.tryAcquire(ADDRESS_1.getAddress());
        assertNotNull(pending);
        assertEquals(1, admission.pendingLeases());

        assertTrue(handler.writeToken(out(), dcid(), ADDRESS_2),
                "one pending of four open is a backlog: NEVER must still send a Retry");

        pending.confirm();
        assertEquals(0, admission.pendingLeases());
        assertFalse(handler.writeToken(out(), dcid(), ADDRESS_3), "confirmed: NEVER writes no token again");
        pending.close();
    }

    // ── 11. full open slot ───────────────────────────────────────────────

    @Test
    void fullOpenSlotForcesRetryInEveryMode() {
        for (TransportConfig.RetryMode mode : TransportConfig.RetryMode.values()) {
            AtomicLong retries = new AtomicLong();
            ConnectionAdmission admission = admission(1000, 100, 4, 1, 1000);
            ConnectionAdmission.Lease held = admission.tryAcquire(ADDRESS_1.getAddress());
            assertNotNull(held, "the test holds the single open lease");

            AdmissionTokenHandler handler = handler(admission, admission(1000, 100, 4, 1, 1000), mode, retries);
            assertTrue(handler.writeToken(out(), dcid(), ADDRESS_1),
                    mode + " must force a Retry while the open slot is full");

            held.close();
        }
    }

    @Test
    void validTokenIsDroppedWhileTheOpenSlotIsFullAndAcceptedAfterItFrees() {
        AtomicLong retries = new AtomicLong();
        ConnectionAdmission admission = admission(1000, 100, 4, 1, 1000);
        ConnectionAdmission validated = admission(1000, 100, 4, 1, 1000);
        ConnectionAdmission.Lease held = admission.tryAcquire(ADDRESS_1.getAddress());
        assertNotNull(held);

        AdmissionTokenHandler handler = handler(admission, validated, TransportConfig.RetryMode.ALWAYS, retries);
        ByteBuf heldToken = token(handler, ADDRESS_1);
        assertEquals(-1, handler.validateToken(heldToken, ADDRESS_1),
                "a valid token is dropped while the open slot is full");

        held.close();
        assertEquals(0, admission.pendingLeases(), "closing the held lease left no pending lease");

        ByteBuf freshToken = token(handler, ADDRESS_1);
        assertEquals(PREFIX_LENGTH, handler.validateToken(freshToken, ADDRESS_1),
                "after the lease is freed a fresh valid token is accepted");
    }

    // ── 12. validateToken limits ─────────────────────────────────────────

    @Test
    void validateAcceptsAValidTokenWithinLimits() {
        AtomicLong retries = new AtomicLong();
        ConnectionAdmission admission = admission(1000, 100, 100, 100, 1000);
        AdmissionTokenHandler handler = handler(admission, admission(1000, 10, 100, 100, 1000),
                TransportConfig.RetryMode.ALWAYS, retries);

        ByteBuf token = token(handler, ADDRESS_1);
        assertEquals(PREFIX_LENGTH, handler.validateToken(token, ADDRESS_1));
    }

    @Test
    void tamperedTokenIsRejectedAndSpendsNoValidatedAttempt() {
        AtomicLong retries = new AtomicLong();
        ConnectionAdmission admission = admission(1000, 100, 100, 100, 1000);
        ConnectionAdmission validated = admission(1000, 1, 100, 100, 1000);
        AdmissionTokenHandler handler = handler(admission, validated, TransportConfig.RetryMode.ALWAYS, retries);

        ByteBuf token = token(handler, ADDRESS_1);
        byte[] bytes = new byte[token.readableBytes()];
        token.getBytes(token.readerIndex(), bytes);
        bytes[9] ^= 0x01; // flip one HMAC tag byte
        ByteBuf tampered = owned(Unpooled.wrappedBuffer(bytes));

        assertEquals(-1, handler.validateToken(tampered, ADDRESS_1), "a tampered token is rejected");
        assertTrue(validated.allowAttempt(ADDRESS_1.getAddress()),
                "the tampered token spent no validated attempt: the only one is still available");
        assertFalse(validated.allowAttempt(ADDRESS_1.getAddress()),
                "the validated budget of one is now exhausted, so the rejection spent nothing");
    }

    @Test
    void validTokenOverTheValidatedAttemptLimitIsRejected() {
        AtomicLong retries = new AtomicLong();
        ConnectionAdmission admission = admission(1000, 100, 100, 100, 1000);
        ConnectionAdmission validated = admission(1000, 1, 100, 100, 1000);
        AdmissionTokenHandler handler = handler(admission, validated, TransportConfig.RetryMode.ALWAYS, retries);

        ByteBuf token = token(handler, ADDRESS_1);
        assertEquals(PREFIX_LENGTH, handler.validateToken(token, ADDRESS_1), "a valid token within limits is accepted");
        assertEquals(-1, handler.validateToken(token, ADDRESS_1),
                "the same valid token over the attempt limit is dropped");
        assertEquals(0, admission.pendingLeases(), "a rejected token took no lease");
    }

    @Test
    void validTokenRefusedOnceTheValidatedGlobalBudgetIsSpentFromOtherIps() {
        AtomicLong retries = new AtomicLong();
        ConnectionAdmission admission = admission(1000, 100, 100, 100, 1000);
        ConnectionAdmission validated = admission(2, 100, 100, 100, 2);
        AdmissionTokenHandler handler = handler(admission, validated, TransportConfig.RetryMode.ALWAYS, retries);

        ByteBuf token1 = token(handler, ADDRESS_1);
        ByteBuf token2 = token(handler, ADDRESS_2);
        ByteBuf token3 = token(handler, ADDRESS_3);

        assertEquals(PREFIX_LENGTH, handler.validateToken(token1, ADDRESS_1), "first valid token spends one");
        assertEquals(PREFIX_LENGTH, handler.validateToken(token2, ADDRESS_2), "second valid token spends the last");
        assertEquals(-1, handler.validateToken(token3, ADDRESS_3),
                "the validated global budget is exhausted, so the third IP is refused");
    }

    // ── 13. the open-slot probe leaves nothing behind ────────────────────

    @Test
    void openSlotProbeLeavesNoLeaseBehind() {
        AtomicLong retries = new AtomicLong();
        ConnectionAdmission admission = admission(1000, 500, 10, 3, 1000);
        AdmissionTokenHandler handler = handler(admission, admission(1000, 500, 10, 3, 1000),
                TransportConfig.RetryMode.NEVER, retries);

        for (int i = 0; i < 100; i++) {
            handler.writeToken(out(), dcid(), ADDRESS_1);
        }
        assertEquals(0, admission.pendingLeases(), "the probe left no pending lease behind");

        ConnectionAdmission.Lease[] leases = new ConnectionAdmission.Lease[3];
        for (int i = 0; i < leases.length; i++) {
            leases[i] = admission.tryAcquire(ADDRESS_1.getAddress());
            assertNotNull(leases[i], "probe " + i + " must not have left an open lease behind");
        }
        assertNull(admission.tryAcquire(ADDRESS_1.getAddress()), "the per-IP open cap still holds");
        for (ConnectionAdmission.Lease lease : leases) {
            lease.close();
        }
    }

    // ── 14. token length ─────────────────────────────────────────────────

    @Test
    void maxTokenLengthIs61() {
        AdmissionTokenHandler handler = handler(admission(1000, 100, 100, 100, 1000),
                admission(1000, 100, 100, 100, 1000), TransportConfig.RetryMode.NEVER, new AtomicLong());
        assertEquals(61, handler.maxTokenLength());
    }
}
