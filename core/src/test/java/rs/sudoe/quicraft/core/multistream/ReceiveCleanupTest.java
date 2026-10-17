// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiveCleanupTest {
    @Test
    void throwingCleanupNeverSkipsLaterOwners() {
        List<Integer> visited = new ArrayList<Integer>();
        RuntimeException first = new IllegalStateException("unit release");
        Error second = new AssertionError("inflater release");
        RuntimeException failure = assertThrows(RuntimeException.class, () -> ReceiveCleanup.run(() -> {
            visited.add(1);
            throw first;
        }, () -> {
            visited.add(2);
            throw second;
        }, () -> visited.add(3)));
        assertSame(first, failure);
        assertArrayEquals(new Throwable[] {second}, failure.getSuppressed());
        assertEquals(Arrays.asList(1, 2, 3), visited);
    }
}
