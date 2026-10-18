// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.core.multistream.MultistreamSession;

/**
 * Immutable snapshot of one logical server-bound game send (U3-W2a). The envelope is created once
 * at the outermost {@code Connection.send} entry, before vanilla schedules anything, and its
 * identity and producer facts never change afterwards. The pending-send task and the event-loop
 * dispatch runnable carry this object itself, so a later stage reads the envelope that was
 * snapshotted at send time instead of looking a packet up again.
 *
 * <p>The {@link ProducerSnapshot} lists are copied and unmodifiable; the scope and token handles
 * are the opaque session identities from {@link MultistreamSession}, not copies. {@link #epoch()}
 * is stored only: this unit does not settle stale work, advance a lifecycle, or replace a session.
 */
public final class CausalSendEnvelope {
    private final long sendId;
    private final Object connection;
    private final @Nullable Object sessionOwner;
    private final long epoch;
    private final ProducerSnapshot producer;
    private final BundleMetadata bundle;

    CausalSendEnvelope(long sendId, Object connection, @Nullable Object sessionOwner, long epoch,
            ProducerSnapshot producer, BundleMetadata bundle) {
        if (sendId <= 0) {
            throw new IllegalArgumentException("sendId must be positive");
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
        this.sendId = sendId;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sessionOwner = sessionOwner;
        this.epoch = epoch;
        this.producer = Objects.requireNonNull(producer, "producer");
        this.bundle = Objects.requireNonNull(bundle, "bundle");
    }

    /** Unique within the connection that allocated it; never reused by that connection. */
    public long sendId() {
        return sendId;
    }

    /** The connection this send belongs to. Identity only, never a lookup key. */
    public Object connection() {
        return connection;
    }

    /** The packet listener that owned the session when the send was snapshotted, or null. */
    public @Nullable Object sessionOwner() {
        return sessionOwner;
    }

    /** The lifecycle epoch stored by the connection at send time. */
    public long epoch() {
        return epoch;
    }

    /** Immutable producer scopes, update tokens and ACK/batch operation facts of this send. */
    public ProducerSnapshot producer() {
        return producer;
    }

    /** Whether this send was a bundle and its ordered children, if any. */
    public BundleMetadata bundle() {
        return bundle;
    }

    @Override
    public String toString() {
        return "CausalSendEnvelope[sendId=" + sendId + ", epoch=" + epoch + ", bundle="
                + bundle.bundle() + ", children=" + bundle.children().size() + "]";
    }

    /**
     * The producer facts captured together with the send: the scopes and update tokens that cover
     * it, plus the ACK or batch operation it participates in. All lists are immutable copies; the
     * handles themselves are the session's opaque identities.
     */
    public record ProducerSnapshot(List<MultistreamSession.CausalScope> scopes,
            List<MultistreamSession.UpdateToken> tokens, @Nullable AckOperation ack,
            @Nullable BatchOperation batch) {
        public static final ProducerSnapshot EMPTY = new ProducerSnapshot(List.of(), List.of(), null, null);

        public ProducerSnapshot {
            scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
            tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
        }
    }

    /**
     * One ACK production fact: the connection-local operation id, the sequence the packet carries,
     * and the pending action sequences it covers, in the order the producer recorded them. Sequence
     * 0 is valid (it is the observed abort value) and is kept as recorded.
     */
    public record AckOperation(long ackId, int sequence, List<Integer> coveredSequences) {
        public AckOperation {
            if (ackId <= 0) {
                throw new IllegalArgumentException("ackId must be positive");
            }
            if (sequence < 0) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
            coveredSequences = List.copyOf(Objects.requireNonNull(coveredSequences, "coveredSequences"));
        }
    }

    /** The observed batch boundaries, so a child can be attributed to the operation that wrote it. */
    public enum BatchKind {
        START,
        INSTALL,
        FINISH
    }

    /**
     * One batch operation fact: the connection-local batch id, its boundary kind, the child index
     * within the batch (0 when not an install) and the batch size the producer expects.
     */
    public record BatchOperation(long batchId, BatchKind kind, int index, int expected) {
        public BatchOperation {
            if (batchId <= 0) {
                throw new IllegalArgumentException("batchId must be positive");
            }
            Objects.requireNonNull(kind, "kind");
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative");
            }
            if (expected < 0) {
                throw new IllegalArgumentException("expected must not be negative");
            }
        }
    }

    /** One bundle child in wire order: its position in the bundle and the exact packet object. */
    public record BundleChild(int index, Object packet) {
        public BundleChild {
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative");
            }
            Objects.requireNonNull(packet, "packet");
        }
    }

    /**
     * The ordered bundle metadata of one send. A non-bundle send carries {@link #NONE}; a bundle may
     * be empty. The indices must be exactly 0 to {@code size - 1}, so the order is explicit and a
     * rewritten list cannot silently reorder the children.
     */
    public record BundleMetadata(boolean bundle, List<BundleChild> children) {
        public static final BundleMetadata NONE = new BundleMetadata(false, List.of());

        public BundleMetadata {
            children = List.copyOf(Objects.requireNonNull(children, "children"));
            if (!bundle && !children.isEmpty()) {
                throw new IllegalArgumentException("a non-bundle send cannot carry bundle children");
            }
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).index() != i) {
                    throw new IllegalArgumentException("bundle child indices must be 0 to size - 1 in order");
                }
            }
        }

        /** The metadata of a bundle send whose children were read once, in iteration order. */
        public static BundleMetadata of(List<BundleChild> children) {
            return new BundleMetadata(true, children);
        }
    }
}
