// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/**
 * Per-stream, per-direction sequence checker (docs/protocol.md 13.4): sequences start at 1 and
 * increment by exactly one per record, including END. A skipped, duplicate or out-of-order
 * sequence is a protocol error, and the maximum unsigned value cannot be followed (no wrap).
 * Not thread-safe: one instance per direction.
 */
public final class SequenceChecker {
    private long expected = 1;
    private boolean exhausted;

    public SequenceChecker() {}

    /** For tests: the first expected value. */
    SequenceChecker(long firstExpected) {
        this.expected = firstExpected;
    }

    /** The sequence value the next record must carry. */
    public long expected() {
        return expected;
    }

    public void check(long sequence) throws MultistreamProtocolException {
        if (exhausted) {
            throw new MultistreamProtocolException(MultistreamProtocolException.CLOSE_ORDERING,
                    "13.4", "record after the maximum sequence (no wrap)");
        }
        if (sequence == 0) {
            throw new MultistreamProtocolException(MultistreamProtocolException.CLOSE_ORDERING,
                    "13.4", "record sequence 0");
        }
        if (Long.compareUnsigned(sequence, expected) != 0) {
            throw new MultistreamProtocolException(MultistreamProtocolException.CLOSE_ORDERING,
                    "13.4", "expected sequence " + Long.toUnsignedString(expected) + ", got "
                            + Long.toUnsignedString(sequence));
        }
        if (expected == -1L) {
            // Only reachable from the test constructor; the maximum was accepted.
            exhausted = true;
        } else {
            expected++;
        }
    }
}
