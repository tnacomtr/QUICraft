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
    /** Local-only expiry diagnostic for logs; never sent on the wire. Null for every other close. */
    private final String diagnostic;

    private ReceiveClose(int errorCode, boolean unseenPrerequisite, String detail, String diagnostic) {
        this.errorCode = errorCode;
        this.unseenPrerequisite = unseenPrerequisite;
        this.detail = detail;
        this.diagnostic = diagnostic;
    }

    /** Peer violation: 0x11, never with the phrase. */
    public static ReceiveClose peerViolation(String detail) {
        return new ReceiveClose(PEER_VIOLATION, false, detail, null);
    }

    /** Refused local resource wait expired (10 s transport clock, no stall exclusion): 0x12, no phrase. */
    public static ReceiveClose resourceWait(String detail) {
        return new ReceiveClose(RESOURCE, false, detail, null);
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
        return blockedExpiry(stallConsumed, locallyWithheld, null);
    }

    /**
     * As {@link #blockedExpiry(boolean, boolean)}, carrying the local-only diagnostic of the expired wait. The wire
     * bytes, the error code and the phrase rule are the same with or without the diagnostic.
     *
     * @param diagnostic the local log text of the expiry, or null
     */
    public static ReceiveClose blockedExpiry(boolean stallConsumed, boolean locallyWithheld, String diagnostic) {
        boolean phrase = !stallConsumed && !locallyWithheld;
        return new ReceiveClose(RESOURCE, phrase, phrase ? "blocked-head expiry" : "generic resource", diagnostic);
    }

    /**
     * Absolute retention expiry of a blocked head (13.10, 13.12): generic 0x12 with no phrase. Renewals never
     * delay it, and it is not a counted unseen-prerequisite event.
     */
    public static ReceiveClose retentionExpiry() {
        return retentionExpiry(null);
    }

    /** As {@link #retentionExpiry()}, carrying the local-only diagnostic of the expired wait. */
    public static ReceiveClose retentionExpiry(String diagnostic) {
        return new ReceiveClose(RESOURCE, false, "blocked-head retention", diagnostic);
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

    /**
     * Local-only diagnostic of an expired blocked-head wait, or null. It is appended to the local log and exception
     * text only; it is never encoded on the wire.
     */
    public String diagnostic() {
        return diagnostic;
    }
}
