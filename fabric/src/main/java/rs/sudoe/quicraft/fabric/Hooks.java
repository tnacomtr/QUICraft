// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fail-safe support for every hook (CLAUDE.md): each hook runs its QUICraft code inside a try
 * block that starts with {@link #enter} and ends in {@link #failed}, then carries on down the
 * vanilla TCP path.
 *
 * <p>Fault injection: with {@code -Dquicraft.faultInjection=true} (or {@link #setFaultInjection} in
 * tests) every hook throws as soon as it is entered, so tests can prove that TCP joins and pings
 * still work with QUICraft broken.
 */
public final class Hooks {
    private static volatile java.util.function.Predicate<String> faultInjection =
            Boolean.getBoolean("quicraft.faultInjection") ? hook -> true : hook -> false;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final Set<String> INJECTED = ConcurrentHashMap.newKeySet();

    private Hooks() {}

    public static void enter(String hook) {
        if (faultInjection.test(hook)) {
            INJECTED.add(hook);
            throw new IllegalStateException("QUICraft fault injection: " + hook);
        }
    }

    /** Tests only: make every hook throw from now on (true) or stop (false). */
    public static void setFaultInjection(boolean enabled) {
        if (enabled) {
            INJECTED.clear();
        }
        faultInjection = enabled ? hook -> true : hook -> false;
    }

    /** Tests only: make just these hooks throw. */
    public static void setFaultInjection(String... hooks) {
        Set<String> only = Set.of(hooks);
        INJECTED.clear();
        faultInjection = only::contains;
    }

    /** Tests: whether the named injection actually threw since the last enabled injection was set. */
    public static boolean wasFaultInjected(String hook) {
        return INJECTED.contains(hook);
    }

    /** Tests: how many distinct hook sites actually threw since injection was enabled. */
    public static int faultInjectedCount() {
        return INJECTED.size();
    }

    /**
     * Logs a hook's failure once per hook; the caller then takes the vanilla path. Never throws: it runs
     * inside the hooks' catch blocks, so a failing logger must not stop the vanilla code that follows.
     */
    public static void failed(String hook, Throwable t) {
        try {
            if (WARNED.add(hook)) {
                QuicraftFabric.LOG.warn("QUICraft: {} failed; continuing as vanilla (TCP). Logged once.", hook, t);
            }
        } catch (Throwable ignored) {
            // Logging is best effort; the vanilla path matters more.
        }
    }
}
