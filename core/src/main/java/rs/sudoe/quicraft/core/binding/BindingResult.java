// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

/**
 * Typed outcome of one binding step (docs/protocol.md §12.9).
 *
 * <ul>
 *   <li>{@link Status#SEND}: the caller must send {@link #message()} and then wait for the peer.</li>
 *   <li>{@link Status#ACTIVE}: activation is complete. The server's final {@code SERVER_SWITCH}
 *       still has to be written, so it is carried as {@link #message()} (null for the client, which
 *       has nothing left to send).</li>
 *   <li>{@link Status#FAILED}: fatal; the caller must close the whole QUIC connection with
 *       application error {@code 0x10} and reason {@link #failure()}.</li>
 * </ul>
 */
public final class BindingResult {
    public enum Status {
        SEND,
        ACTIVE,
        FAILED
    }

    private final Status status;
    private final BindingMessage message;
    private final BindingFailure failure;

    private BindingResult(Status status, BindingMessage message, BindingFailure failure) {
        this.status = status;
        this.message = message;
        this.failure = failure;
    }

    /** The next message to send. */
    public static BindingResult send(BindingMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }
        return new BindingResult(Status.SEND, message, null);
    }

    /**
     * Activation complete. {@code finalMessage} is the server's SERVER_SWITCH that still must be
     * written, or null on the client.
     */
    public static BindingResult active(BindingMessage finalMessage) {
        return new BindingResult(Status.ACTIVE, finalMessage, null);
    }

    /** Fatal outcome with a reason code. */
    public static BindingResult failed(BindingFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure is required");
        }
        return new BindingResult(Status.FAILED, null, failure);
    }

    public Status status() {
        return status;
    }

    /** Non-null for {@link Status#SEND}, and for the server on {@link Status#ACTIVE}. */
    public BindingMessage message() {
        return message;
    }

    public boolean isSend() {
        return status == Status.SEND;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }

    /** Non-null only for {@link Status#FAILED}. */
    public BindingFailure failure() {
        return failure;
    }

    @Override
    public String toString() {
        return "BindingResult{status=" + status
                + (failure != null ? ", failure=" + failure : "")
                + (message != null ? ", message=" + message : "") + '}';
    }
}