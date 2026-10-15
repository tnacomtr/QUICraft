// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Fault injection for core's own fail-safe points (CLAUDE.md "Working rules"): a stage listed
 * here throws where core would otherwise do its work, and core must carry on as if that work
 * weren't there. Off unless a test calls {@link #set} (the Fabric gametests do too). No system
 * property maps onto these stages; {@code -Dquicraft.faultInjection} drives the platform hooks'
 * own fault predicate, not this class.
 */
public final class Faults {
    /** The 0-RTT stages, docs/protocol.md §8. */
    public static final String EARLY_SEND = "early send";
    public static final String EARLY_RECORD = "early record";
    public static final String EARLY_COMPARE = "early compare";
    public static final String EARLY_TOKEN_ISSUE = "early token issue";
    public static final String EARLY_TOKEN_REDEEM = "early token redeem";
    /** The QUIC attempt fails right after its 0-RTT data went out: TCP wins with a login started. */
    public static final String EARLY_ABANDON = "early abandon";
    /** Scheduling a server connection's handshake deadline fails (docs/protocol.md §9). */
    public static final String HANDSHAKE_DEADLINE = "handshake deadline";

    /**
     * The pre-connect {@code /3} selection fails: the pin and re-bind state read, the routing
     * table lookup and the selection rules of 12.3, 13.1 and 12.16. The connect path must carry
     * on with {@code /2} or TCP in the same attempt.
     */
    public static final String V3_SELECT = "v3 select";
    /** The {@code /3} QUIC dial fails (12.3); the attempt races or falls back under §5. */
    public static final String V3_DIAL = "v3 dial";
    /** The server's side of a {@code /3} connection fails while it accepts or activates it (13.1, 13.3). */
    public static final String V3_ACCEPT = "v3 accept";
    /** The TLS exporter value of 12.7 cannot be obtained; that is a fatal {@code 0x10}. */
    public static final String BINDING_EXPORTER = "binding exporter";
    /** One binding step of 12.9 fails; that is a fatal {@code 0x10}. */
    public static final String BINDING_STEP = "binding step";
    /** Activating a lane of 13.3 fails after the binding exchange; that is a fatal {@code 0x10}. */
    public static final String LANE_ACTIVATE = "lane activate";
    /** Persisting an authenticated pin of 12.15 fails; that is a fatal {@code 0x10}. */
    public static final String PIN_WRITE = "pin write";

    private static volatile Set<String> stages = Collections.emptySet();

    private Faults() {}

    /** Throws if {@code stage} is on. */
    public static void check(String stage) {
        if (stages.contains(stage) || stages.contains("*")) {
            throw new IllegalStateException("QUICraft fault injection: " + stage);
        }
    }

    /** The stages to fail; none to turn fault injection off, {@code "*"} for all. */
    public static void set(String... failing) {
        stages = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(failing)));
    }
}
