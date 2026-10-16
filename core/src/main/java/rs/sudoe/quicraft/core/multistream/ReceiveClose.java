// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import rs.sudoe.quicraft.core.CloseReason;
import rs.sudoe.quicraft.core.Protocol;

/**
 * The close a receive-policy decision requires (protocol.md 13.10 and 13.12, "Deadline precedence").
 *
 * <ul>
 *   <li>{@code 0x11} ({@link MultistreamProtocolException#CLOSE_ORDERING}): a peer violation
 *       (invalid index, skipped or duplicate sequence, stale or future epoch, bad shard or join,
 *       excessive dependencies, same-stream entry, provable cycle, REALTIME cap breach).</li>
 *   <li>{@code 0x12} ({@link Protocol#CLOSE_RESOURCE}): a local or resource close. It carries the
 *       ASCII reason phrase {@link CloseReason#UNSEEN_PREREQUISITE} only for an expired blocked-head
 *       wait that consumed no recorded stall time and withheld nothing locally. Every other
 *       resource close has no phrase.</li>
 * </ul>
 */
public final class ReceiveClose {
    /** Peer-attributed violation close code, 0x11. */
    public static final int PEER_VIOLATION = MultistreamProtocolException.CLOSE_ORDERING;
    /** Local or resource close code, 0x12. */
    public static final int RESOURCE = Protocol.CLOSE_RESOURCE;

    private final int errorCode;
    private final boolean unseenPrerequisite;
    private final String detail;

    private ReceiveClose(int errorCode, boolean unseenPrerequisite, String detail) {
        this.errorCode = errorCode;
        this.unseenPrerequisite = unseenPrerequisite;
        this.detail = detail;
    }

    /** Peer violation: 0x11, never with the phrase. */
    public static ReceiveClose peerViolation(String detail) {
        return new ReceiveClose(PEER_VIOLATION, false, detail);
    }

    /** Refused local resource wait expired (10 s transport clock, no stall exclusion): 0x12, no phrase. */
    public static ReceiveClose resourceWait(String detail) {
        return new ReceiveClose(RESOURCE, false, detail);
    }

    /**
     * Blocked-head expiry: always 0x12 (never 0x11, since the receiver cannot tell honest loss from a
     * forged dependency). The phrase is added only when the wait consumed no recorded stall time and
     * no part of the prerequisite chain was withheld locally.
     *
     * @param stallConsumed true if the expired wait excluded any recorded tick-stall time
     * @param locallyWithheld true if any prerequisite was withheld locally (node pool, read grant or decode refusal)
     */
    public static ReceiveClose blockedExpiry(boolean stallConsumed, boolean locallyWithheld) {
        boolean phrase = !stallConsumed && !locallyWithheld;
        return new ReceiveClose(RESOURCE, phrase, phrase ? "blocked-head expiry" : "generic resource");
    }

    /** Returns 0x11 or 0x12. */
    public int errorCode() {
        return errorCode;
    }

    /** Reports whether this close must carry the {@code unseen-prerequisite} reason phrase. */
    public boolean unseenPrerequisite() {
        return unseenPrerequisite;
    }

    /** Returns the reason phrase bytes, or null when the close carries none. */
    public byte[] reason() {
        return unseenPrerequisite ? CloseReason.UNSEEN_PREREQUISITE.clone() : null;
    }

    /** Human-readable cause for logs. */
    public String detail() {
        return detail;
    }
}
