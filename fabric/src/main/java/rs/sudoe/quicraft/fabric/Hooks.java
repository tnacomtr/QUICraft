// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fail-safe support for every hook (CLAUDE.md): each hook runs its QUICraft code inside a try
 * block that starts with {@link #enter} and ends in {@link #failed}, then carries on down the
 * vanilla TCP path.
 *
 * <p>Fault injection: with {@code -Dquicraft.faultInjection=true} every hook throws as soon as it
 * is entered, so tests can prove that TCP joins and pings still work with QUICraft broken.
 */
public final class Hooks {
    public static final boolean FAULT_INJECTION = Boolean.getBoolean("quicraft.faultInjection");
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private Hooks() {}

    public static void enter(String hook) {
        if (FAULT_INJECTION) {
            throw new IllegalStateException("QUICraft fault injection: " + hook);
        }
    }

    /** Logs a hook's failure once per hook; the caller then takes the vanilla path. */
    public static void failed(String hook, Throwable t) {
        if (WARNED.add(hook)) {
            QuicraftFabric.LOG.warn("QUICraft: {} failed; continuing as vanilla (TCP). Logged once.", hook, t);
        }
    }
}
