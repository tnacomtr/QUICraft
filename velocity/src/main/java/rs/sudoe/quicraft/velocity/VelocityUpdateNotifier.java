// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import com.velocitypowered.api.proxy.Player;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;

/**
 * Update notices for admins (docs/updater.md 6, Notifications). Each announced message goes to the
 * online players with {@link #NOTIFY_PERMISSION} at once. A player who joins later gets the remembered
 * message once, until the next announcement replaces it.
 *
 * <p>{@link #announce} may run on the updater worker thread. Velocity's {@code Player#sendMessage} is
 * safe to call from there, so no hop to another thread is needed.
 */
final class VelocityUpdateNotifier {
    static final String NOTIFY_PERMISSION = "quicraft.admin.update.notify";

    private final Supplier<Collection<Player>> online;
    private final Object lock = new Object();
    /** Guarded by {@link #lock}: the last announced message, or null before the first one. */
    private String last;
    /** Guarded by {@link #lock}: admins who already have {@link #last}. */
    private final Set<UUID> delivered = new HashSet<>();

    VelocityUpdateNotifier(Supplier<Collection<Player>> online) {
        this.online = online;
    }

    /**
     * Remembers the message as the current one and sends it to the online admins now. Only the roster is
     * snapshotted under the lock; the permission checks and the sends run outside it, because Velocity may
     * resolve a permission through a plugin that itself talks to the proxy.
     */
    void announce(String message) {
        List<Player> roster;
        synchronized (lock) {
            last = message;
            delivered.clear();
            roster = new ArrayList<>(online.get());
        }
        for (Player p : roster) {
            if (!isAdmin(p)) {
                continue;
            }
            synchronized (lock) {
                delivered.add(p.getUniqueId());
            }
            send(p, message);
        }
    }

    /** Sends the current message to a player who has not had it yet and may see it. */
    void onJoin(Player player) {
        synchronized (lock) {
            if (last == null) {
                return;
            }
        }
        if (!isAdmin(player)) {
            return;
        }
        String message;
        synchronized (lock) {
            if (!delivered.add(player.getUniqueId())) {
                return;
            }
            message = last;
        }
        send(player, message);
    }

    private static boolean isAdmin(Player p) {
        return p.hasPermission(NOTIFY_PERMISSION);
    }

    private static void send(Player p, String message) {
        try {
            p.sendMessage(Component.text(message));
        } catch (RuntimeException e) {
            // One player's broken connection must not stop the other notices.
        }
    }
}
