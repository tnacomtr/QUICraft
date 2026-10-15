// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

/**
 * Typed outcome of building a {@link BindingStateMachine} (docs/protocol.md §12.3, §12.14): either
 * a machine that is ready to run the exchange, or the fatal {@link BindingFailure#EXPORTER_ERROR}
 * the caller must close the whole QUIC connection with, application error {@code 0x10}.
 */
public final class BindingMachineResult {
    private final BindingStateMachine machine;
    private final BindingFailure failure;

    private BindingMachineResult(BindingStateMachine machine, BindingFailure failure) {
        this.machine = machine;
        this.failure = failure;
    }

    /** A ready machine. */
    public static BindingMachineResult ok(BindingStateMachine machine) {
        if (machine == null) {
            throw new IllegalArgumentException("machine is required");
        }
        return new BindingMachineResult(machine, null);
    }

    /** A fatal exporter error: no machine was built. */
    public static BindingMachineResult failed(BindingFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure is required");
        }
        return new BindingMachineResult(null, failure);
    }

    public boolean isOk() {
        return machine != null;
    }

    public boolean isFailed() {
        return failure != null;
    }

    /** The built machine, or null when {@link #isFailed()}. */
    public BindingStateMachine machine() {
        return machine;
    }

    /** The fatal reason, or null when {@link #isOk()}. */
    public BindingFailure failure() {
        return failure;
    }

    @Override
    public String toString() {
        return "BindingMachineResult{" + (machine != null ? "machine=" + machine : "failure=" + failure)
                + '}';
    }
}
