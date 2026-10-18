// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;

/**
 * The server-side switch that turns {@code /3} on (docs/protocol.md 12.1, 12.3, 13.1, 13.13): the
 * one place that decides whether a build may advertise {@code quicraft/3} and run a {@code /3}-only
 * listener, and the pieces that listener needs.
 *
 * <p>The flag is off by default and the feature is disabled unless every 12.3 prerequisite holds.
 * A server that cannot satisfy any of them keeps today's {@code quicraft/2} listener and
 * advertisement unchanged, and logs once why: a false capability probe is an ordinary compatibility
 * condition, not a failure (12.3).</p>
 *
 * <p>The conditions, in the order {@link #decide} applies them:</p>
 * <ol>
 *   <li>the multistream setting is on (12.1);</li>
 *   <li>the loaded QUIC native can export TLS keying material
 *       ({@link QuicSupport#hasKeyingMaterialExport()}, 12.2 and 12.3): without it channel binding
 *       cannot run, and 12.7 makes an exporter error fatal rather than permission to continue;</li>
 *   <li>a routing resource loaded, because 13.1 selects {@code /3} only against a matching digest,
 *       and this server advertises the digests it has ({@link #advertisedRouting()});</li>
 *   <li>the loaded native can cap quiche's flow-control windows
 *       ({@link QuicSupport#hasMaxFlowControlWindows()}, 13.13). This one is not in 12.3's list but
 *       follows from 13.1: a {@code /3} advertisement must name a listener that really speaks
 *       {@code /3}, and {@link rs.sudoe.quicraft.core.transport.QuicServer} refuses to bind one
 *       without those caps. Advertising {@code /3} while listening as {@code /2} would break every
 *       client that trusted the advertisement.</li>
 * </ol>
 *
 * <p>Nothing here starts a listener or a Minecraft server: {@link #decide} is a pure function of
 * four probes, and {@link #probe(boolean)} is the same decision over the real ones.</p>
 */
public final class MultistreamSwitch {
    /**
     * The protocol numbers this build ships a routing resource for (13.7), in advertisement order:
     * the 26.x placeholder today, the version spread's tables later. A number without a resource is
     * not advertised, and 13.1 then sends that client to TCP.
     */
    private static final List<Integer> SHIPPED_PROTOCOLS =
            Collections.unmodifiableList(Arrays.asList(Integer.valueOf(775)));

    /** Why {@code /3} is on or off, with the one line to log for it. */
    public enum Reason {
        /** Every prerequisite holds: the listener is {@code /3}-only and the advertisement says so. */
        ENABLED("multi-stream is enabled"),
        /** The disabled-by-default flag is off: today's behaviour, unchanged (12.1). */
        FLAG_OFF("the multistream setting is off"),
        /** No TLS exporter: channel binding cannot run, so {@code /3} must not be offered (12.2, 12.3). */
        NO_EXPORTER("the QUIC native cannot export TLS keying material (12.2, 12.3); staying on"
                + " quicraft/2"),
        /** No routing table for any shipped protocol number: 13.1 cannot select {@code /3} without one. */
        NO_ROUTING("no routing resource loaded (13.7); staying on quicraft/2"),
        /** A table loads but none is the reviewed schema 2 that 13.7 requires for {@code /3}. */
        NO_ROUTING_SCHEMA("no reviewed schema-2 routing resource loaded (13.7); staying on quicraft/2"),
        /** The native cannot cap quiche's flow-control windows, which 13.13 requires for {@code /3}. */
        NO_WINDOW_CAPS("the QUIC native cannot cap quiche's flow-control windows (13.13); staying on"
                + " quicraft/2");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        /** True only for {@link #ENABLED}. */
        public boolean isEnabled() {
            return this == ENABLED;
        }

        /** The one INFO line to log when this decision is made. */
        public String message() {
            return message;
        }
    }

    private MultistreamSwitch() {}

    /**
     * The decision of 12.3, 13.1 and 13.7 over four probe results, in the order the reasons are
     * checked. {@code routingResourceUsable} means a reviewed schema-2 resource is available (13.7);
     * a loaded table of any other schema counts as unusable and stays on {@code quicraft/2}.
     */
    public static Reason decide(boolean flag, boolean exporterCapable, boolean routingResourceUsable,
            boolean flowControlWindowsCappable) {
        if (!flag) {
            return Reason.FLAG_OFF;
        }
        if (!exporterCapable) {
            return Reason.NO_EXPORTER;
        }
        if (!routingResourceUsable) {
            return Reason.NO_ROUTING;
        }
        if (!flowControlWindowsCappable) {
            return Reason.NO_WINDOW_CAPS;
        }
        return Reason.ENABLED;
    }

    /**
     * {@link #decide} over the real probes on this machine. A flag that is off short-circuits before
     * any probe runs, so the default configuration never touches the native capability checks. A
     * loaded table that is not the reviewed schema 2 (13.7) is {@link Reason#NO_ROUTING_SCHEMA}:
     * the N+2 session refuses it, so advertising {@code /3} would break every client that trusted
     * the advertisement.
     */
    public static Reason probe(boolean flag) {
        return probe(flag, QuicSupport.hasKeyingMaterialExport(), QuicSupport.hasMaxFlowControlWindows());
    }

    /**
     * {@link #probe(boolean)} with the two native probes injected, so the routing/schema decision
     * is testable on any host (a native without the exporter or the window caps would otherwise
     * short-circuit before the routing check).
     */
    static Reason probe(boolean flag, boolean exporterCapable, boolean windowsCappable) {
        if (!flag) {
            return Reason.FLAG_OFF;
        }
        if (!exporterCapable) {
            return Reason.NO_EXPORTER;
        }
        boolean loaded = false;
        for (Integer protocol : SHIPPED_PROTOCOLS) {
            if (tableFor(protocol.intValue()) != null) {
                loaded = true;
                break;
            }
        }
        if (!loaded) {
            return Reason.NO_ROUTING;
        }
        if (advertisedRouting().isEmpty()) {
            return Reason.NO_ROUTING_SCHEMA;
        }
        if (!windowsCappable) {
            return Reason.NO_WINDOW_CAPS;
        }
        return Reason.ENABLED;
    }

    /**
     * The advertisement's {@code routing} member (13.1): every shipped protocol number whose
     * routing resource loads <em>and is the reviewed schema 2</em> (13.7), mapped to
     * {@code sha256:<digest of its exact bytes>}. Empty when none does, which is exactly the
     * {@link Reason#NO_ROUTING_SCHEMA} case. Ascending by protocol number.
     */
    public static Map<Integer, String> advertisedRouting() {
        return advertisedRouting(MultistreamSwitch::tableFor);
    }

    /** {@link #advertisedRouting()} over an injectable table source, so tests can prove both schemas. */
    static Map<Integer, String> advertisedRouting(java.util.function.IntFunction<RoutingTable> tables) {
        Map<Integer, String> routing = new LinkedHashMap<Integer, String>();
        for (Integer protocol : SHIPPED_PROTOCOLS) {
            RoutingTable table = tables.apply(protocol.intValue());
            if (serves(table)) {
                routing.put(protocol, "sha256:" + table.digestHex());
            }
        }
        return Collections.unmodifiableMap(routing);
    }

    /**
     * 13.7: only a reviewed schema-2 resource may drive a {@code /3} join. The N+2 session refuses
     * anything else at activation, so neither the advertisement nor a client's dial may accept it.
     */
    public static boolean serves(RoutingTable table) {
        return table != null && table.schema() == RoutingTableLoader.SCHEMA_VERSION_V2;
    }

    /** Whether the shipped table for this protocol number may drive {@code /3} (13.1, 13.7). */
    public static boolean servesProtocol(int protocolNumber) {
        return serves(tableFor(protocolNumber));
    }

    /** The protocol numbers a routing resource ships for, the ones an advertisement can name. */
    public static List<Integer> shippedProtocols() {
        return SHIPPED_PROTOCOLS;
    }

    /**
     * The routing table a {@code /3} listener serves for one Minecraft protocol number, or null when
     * this build has none: exactly what
     * {@link rs.sudoe.quicraft.core.transport.V3Listener#tableFor(int)} must answer, with null
     * failing the activation closed (12.3, 13.1).
     */
    public static RoutingTable tableFor(int protocolNumber) {
        return RoutingRegistry.forProtocol(protocolNumber).orElse(null);
    }
}
