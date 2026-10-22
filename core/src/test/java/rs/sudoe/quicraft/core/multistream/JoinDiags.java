// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Join marks for the tests of units outside this package, which cannot reach
 * {@link JoinDiag#create(JoinDiag.Side, boolean, LongSupplier, Consumer)}. The in-package tests drive
 * that factory directly; everything else goes through here.
 */
public final class JoinDiags {
    private JoinDiags() {}

    /** One enabled instance whose clock and sink the test owns. */
    public static JoinDiag forTest(JoinDiag.Side side, LongSupplier clock, Consumer<String> sink) {
        return JoinDiag.create(side, true, clock, sink);
    }
}
