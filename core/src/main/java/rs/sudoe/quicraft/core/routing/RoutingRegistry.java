// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.Faults;

/**
 * The routing table this build ships for one Minecraft protocol number, read from core's own
 * classpath resources (docs/protocol.md 13.1, 13.6, 13.7).
 *
 * <p>{@link #forProtocol(int)} is the single lookup the {@code /3} path uses, both to decide before
 * connecting and to advertise: the digest of the table it returns is R, the value the client
 * compares against the advertisement's {@code routing} member (13.1) and the value it writes into
 * its own advertisement. It loads {@code rs/sudoe/quicraft/core/routing/protocol-<n>.json} through
 * {@link RoutingTableLoader}, so a table is validated and its digest is taken over the exact
 * resource bytes, whitespace included (13.7).
 *
 * <p><b>Empty means "no {@code /3} for this protocol number".</b> A missing resource, a resource
 * that is not a valid table, and one that declares another protocol number are all the same answer
 * to the caller: {@link Optional#empty()}, so the selection uses {@code /2} or TCP and never guesses
 * a table (13.1, 13.6). An unusable resource is logged once at INFO, because a build that ships a
 * broken table for a protocol it advertises is worth seeing. Nothing here is ever invented at
 * runtime.
 *
 * <p>No production resource ships yet: the tables are added with the version spread, and until then
 * this returns empty for every protocol number, which can only select {@code /2} or TCP (13.1).
 *
 * <p>Thread-safe. Results, including an empty one, are cached for the lifetime of the process: a
 * classpath resource cannot change while the process runs, and the selection runs on every connect.
 */
public final class RoutingRegistry {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    /** One entry per protocol number: the table, or the empty answer described above. */
    private static final ConcurrentMap<Integer, Optional<RoutingTable>> TABLES =
            new ConcurrentHashMap<Integer, Optional<RoutingTable>>();

    private RoutingRegistry() {}

    /**
     * The table for {@code protocolNumber}, or empty when this build has no usable one.
     *
     * <p>Never throws for a missing or unusable resource. It does throw what
     * {@link Faults#check(String)} throws when the {@link Faults#V3_SELECT} stage is on, so the
     * pre-connect fault injection of the {@code /3} path reaches the registry too; the caller must
     * carry on with {@code /2} or TCP in the same attempt.
     */
    public static Optional<RoutingTable> forProtocol(int protocolNumber) {
        Faults.check(Faults.V3_SELECT);
        if (protocolNumber < 0) {
            // A negative protocol number cannot name a resource: no resource path can spell it, and
            // 13.7 keeps protocol numbers nonnegative.
            return Optional.empty();
        }
        Optional<RoutingTable> cached = TABLES.get(protocolNumber);
        if (cached != null) {
            return cached;
        }
        Optional<RoutingTable> loaded = load(protocolNumber);
        Optional<RoutingTable> previous = TABLES.putIfAbsent(protocolNumber, loaded);
        return previous == null ? loaded : previous;
    }

    private static Optional<RoutingTable> load(int protocolNumber) {
        try {
            return Optional.of(RoutingTableLoader.loadResource(protocolNumber));
        } catch (IOException e) {
            // No such resource: this build has no table for that protocol number.
            return Optional.empty();
        } catch (RoutingTableException e) {
            LOG.log(Level.INFO, "QUICraft routing table for protocol {0} is unusable, so /3 stays "
                    + "off for it: {1}", new Object[] {Integer.valueOf(protocolNumber), e.getMessage()});
            return Optional.empty();
        }
    }
}
