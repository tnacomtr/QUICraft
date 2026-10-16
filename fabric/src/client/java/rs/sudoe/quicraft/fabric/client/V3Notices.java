// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.core.connect.V3Plan;

/**
 * What the player is told about a multi-stream join, held until the join reaches play (the chat
 * does not exist before that): a {@code /3} attempt that failed and fell back to TCP
 * (docs/protocol.md 12.14), and a changed certificate that was re-bound (12.16). Minecraft-free, so
 * the texts are unit-tested; {@link ChatNotices} puts them on screen.
 *
 * <p>Process-wide and short-lived: a fallback queues its notice right before the TCP join starts,
 * and the first play login afterwards takes everything. A join that never reaches play (a failed
 * TCP join) leaves the notice for the next one, which is why {@link #clear()} exists for a new
 * join to the same attempt chain.</p>
 */
final class V3Notices {
    /** A line for the chat: {@code literal} when set, else the translation {@code key} with {@code args}. */
    record Notice(@Nullable String literal, @Nullable String key, String... args) {
        static Notice literal(String text) {
            return new Notice(text, null);
        }

        static Notice translatable(String key, String... args) {
            return new Notice(null, key, args);
        }
    }

    private static final List<Notice> PENDING = new ArrayList<>();

    private V3Notices() {}

    /**
     * The fallback line, exactly {@link V3Plan#fallbackMessage(String)} (the user's wording): it goes
     * to the log when the fallback happens and to the chat once the TCP join reaches play.
     */
    static String fellBack(String reason) {
        String message = V3Plan.fallbackMessage(reason);
        add(Notice.literal(message));
        return message;
    }

    /** Queues a message the connector already built (a failure it handled before the adapter). */
    static void fellBackWith(String message) {
        add(Notice.literal(message));
    }

    /** The server's certificate changed and the re-bind pinned the new one (12.16). */
    static void rebound(String oldFingerprint, String newFingerprint) {
        add(Notice.translatable("quicraft.notice.rebind", oldFingerprint, newFingerprint));
    }

    private static synchronized void add(Notice notice) {
        if (PENDING.size() < 8) {
            PENDING.add(notice);
        }
    }

    /** Everything queued, once. */
    static synchronized List<Notice> drain() {
        List<Notice> all = new ArrayList<>(PENDING);
        PENDING.clear();
        return all;
    }

    static synchronized void clear() {
        PENDING.clear();
    }

    static synchronized boolean isEmpty() {
        return PENDING.isEmpty();
    }
}
