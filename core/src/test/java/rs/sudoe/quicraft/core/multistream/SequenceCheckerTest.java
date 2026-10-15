// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests for {@link SequenceChecker} (docs/protocol.md 13.4): start at 1, +1 each record, no wrap. */
class SequenceCheckerTest {
    @Test
    void acceptsContiguousFromOne() throws Exception {
        SequenceChecker checker = new SequenceChecker();
        assertEquals(1, checker.expected());
        checker.check(1);
        checker.check(2);
        checker.check(3);
        assertEquals(4, checker.expected());
    }

    @Test
    void rejectsZero() {
        SequenceChecker checker = new SequenceChecker();
        assertThrows(MultistreamProtocolException.class, () -> checker.check(0));
    }

    @Test
    void rejectsSkipped() throws Exception {
        SequenceChecker checker = new SequenceChecker();
        checker.check(1);
        assertThrows(MultistreamProtocolException.class, () -> checker.check(3));
    }

    @Test
    void rejectsDuplicate() throws Exception {
        SequenceChecker checker = new SequenceChecker();
        checker.check(1);
        assertThrows(MultistreamProtocolException.class, () -> checker.check(1));
    }

    @Test
    void rejectsOutOfOrderStart() {
        SequenceChecker checker = new SequenceChecker();
        assertThrows(MultistreamProtocolException.class, () -> checker.check(2));
    }

    @Test
    void rejectsWrapAfterMaximum() throws Exception {
        SequenceChecker checker = new SequenceChecker(-1L); // u64 maximum
        checker.check(-1L);
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class,
                () -> checker.check(0));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
    }

    @Test
    void closeCodeIsOrderingFailure() {
        SequenceChecker checker = new SequenceChecker();
        MultistreamProtocolException e =
                assertThrows(MultistreamProtocolException.class, () -> checker.check(5));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
    }
}
