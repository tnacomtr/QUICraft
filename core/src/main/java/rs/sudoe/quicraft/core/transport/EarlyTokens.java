// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A listener's single-use early tokens (docs/protocol.md §8): 16 random bytes, issued on every
 * accepted stream, valid for 24 hours, at most 65 536 outstanding (oldest dropped first),
 * removed when read. In memory only.
 */
final class EarlyTokens {
    static final long LIFETIME_MILLIS = TimeUnit.HOURS.toMillis(24);
    static final int MAX_OUTSTANDING = 65_536;

    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private final int maxOutstanding;
    /** Token to issue time, oldest first. */
    private final LinkedHashMap<ByteBuffer, Long> issued = new LinkedHashMap<>();

    EarlyTokens() {
        this(System::currentTimeMillis, MAX_OUTSTANDING);
    }

    EarlyTokens(LongSupplier clock, int maxOutstanding) {
        this.clock = clock;
        this.maxOutstanding = maxOutstanding;
    }

    synchronized byte[] issue() {
        byte[] token = new byte[Preamble.TOKEN_LENGTH];
        random.nextBytes(token);
        long now = clock.getAsLong();
        expire(now);
        while (issued.size() >= maxOutstanding) {
            Iterator<?> oldest = issued.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        issued.put(ByteBuffer.wrap(token.clone()), now);
        return token;
    }

    /** True if {@code token} was outstanding and unexpired. Removes it either way. */
    synchronized boolean redeem(byte[] token) {
        if (token == null) {
            return false;
        }
        Long at = issued.remove(ByteBuffer.wrap(token));
        return at != null && clock.getAsLong() - at < LIFETIME_MILLIS;
    }

    synchronized int outstanding() {
        return issued.size();
    }

    private void expire(long now) {
        Iterator<Map.Entry<ByteBuffer, Long>> it = issued.entrySet().iterator();
        while (it.hasNext() && now - it.next().getValue() >= LIFETIME_MILLIS) {
            it.remove();
        }
    }
}
