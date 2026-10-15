// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A wall clock a test moves by hand, so an expiry decision (docs/protocol.md 12.15) is made at an
 * exact instant. It is UTC, like the timestamps the pin store persists.
 */
final class TestClock extends Clock {
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final AtomicLong millis;

    TestClock(long startMillis) {
        this.millis = new AtomicLong(startMillis);
    }

    @Override
    public ZoneId getZone() {
        return UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    /** The current time in milliseconds. */
    long now() {
        return millis.get();
    }

    /** Moves the clock {@code delta} milliseconds forward, or backwards when it is negative. */
    long advance(long delta) {
        return millis.addAndGet(delta);
    }
}
