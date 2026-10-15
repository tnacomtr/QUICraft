// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * The one call the connect path makes to expire pins (docs/protocol.md 12.15): forget the expired
 * pins' re-bind state, then remove the pins themselves, in the order that leaks nothing.
 *
 * <p>The two halves live in two files, so they cannot be one atomic write. This order is the safe
 * one: a process that dies after the re-bind state's write and before the pin file's leaves an
 * expired pin, which the next call finds again and finishes, while the opposite order would leave a
 * re-bind backoff and notice history for a pin that is already gone, and nothing would ever remove
 * them. If the re-bind state cannot be written it is disabled, and then the pins are left alone too:
 * a caller that cannot forget a pin's re-bind state does not remove the pin, so the pair never
 * splits.</p>
 *
 * <p>The caller passes the keys of its running attempts, {@link RebindState#activeKeys()}, and a pin
 * whose server is in that set is never expired, nor is its re-bind state forgotten: 12.15 never
 * expires a pin during an active attempt. Use the same snapshot for the lookups that follow
 * ({@link PinStore#pin(String, int, Set)}, {@link PinStore#recordPin(String, int, String, Set)}), so
 * that nothing is left for them to purge on their own; a lookup's own purge does not touch the
 * re-bind state.</p>
 *
 * <p>Call it on a worker: it writes both files synchronously, each with an fsync of the file and of
 * its directory, exactly like the calls it makes.</p>
 */
public final class PinExpiry {
    private PinExpiry() {
    }

    /**
     * Forgets the re-bind state of the pins that have expired at the pin store's clock, then removes
     * those pins (docs/protocol.md 12.15).
     *
     * @param pins the client's pin store
     * @param rebind the client's re-bind state
     * @param activeKeys the logical servers an attempt is running for, from
     *     {@link RebindState#activeKeys()}; empty when there is none
     * @return the keys of the pins that were removed, empty when none had expired, when the re-bind
     *     state could not be written (it is then disabled, and the pins stay for a later call), or
     *     when the pin file itself could not be written (the store is then disabled, and the caller
     *     uses {@code /2} or TCP)
     */
    public static Set<PinKey> purge(PinStore pins, RebindState rebind, Set<PinKey> activeKeys) {
        Objects.requireNonNull(pins, "pins");
        Objects.requireNonNull(rebind, "rebind");
        Objects.requireNonNull(activeKeys, "activeKeys");
        Set<PinKey> expired = pins.expiredKeys(activeKeys);
        if (!expired.isEmpty()) {
            rebind.forgetExpiredPins(expired);
            if (!rebind.isUsable()) {
                // The re-bind entries are not durably gone, so the pins that point at them stay.
                return Collections.emptySet();
            }
        }
        return pins.purgeExpired(activeKeys);
    }
}
