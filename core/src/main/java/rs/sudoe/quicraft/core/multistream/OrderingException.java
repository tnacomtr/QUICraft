// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/** Terminal ordering/resource failure. The owner must close the whole connection. */
public final class OrderingException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public OrderingException(String message) { super(message); }
    public int closeCode() { return 0x11; }
}
