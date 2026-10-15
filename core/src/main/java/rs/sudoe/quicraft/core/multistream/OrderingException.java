// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/** Terminal ordering/resource failure. The owner must close the whole connection. */
public final class OrderingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates a terminal failure with a diagnostic reason, never a payload dump. */
    public OrderingException(String message) {
        super(message);
    }

    /** Returns the connection-level application close code required by 13.12. */
    public int closeCode() {
        return 0x11;
    }
}
