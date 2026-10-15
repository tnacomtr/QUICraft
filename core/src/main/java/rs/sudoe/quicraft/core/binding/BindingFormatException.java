// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

/** Thrown by {@link BindingMessage#decode(byte[])} for a structurally invalid payload. */
public class BindingFormatException extends Exception {
    private static final long serialVersionUID = 1L;

    private final BindingFailure reason;

    public BindingFormatException(BindingFailure reason) {
        super(reason.name());
        this.reason = reason;
    }

    public BindingFailure reason() {
        return reason;
    }
}