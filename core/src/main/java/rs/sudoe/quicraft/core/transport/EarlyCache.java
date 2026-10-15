// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rs.sudoe.quicraft.core.tls.Fingerprint;

/**
 * The client's 0-RTT state, in memory only (docs/protocol.md §8): the latest early token per
 * server (IP, QUIC port, fingerprint, ALPN), and the recorded first flight per server and join
 * inputs. The ALPN is part of the key so {@code /2} tokens and flights are never offered on a
 * {@code /3} dial or the other way round (12.17).
 */
final class EarlyCache {
    static final EarlyCache INSTANCE = new EarlyCache();
    private static final int MAX_ENTRIES = 256;

    private final Map<List<Object>, byte[]> tokens = lru();
    private final Map<List<Object>, byte[]> flights = lru();

    /** The {@code /2} partition. */
    static List<Object> server(InetSocketAddress remote, Fingerprint fingerprint) {
        return server(remote, fingerprint, rs.sudoe.quicraft.core.Protocol.ALPN);
    }

    /** The key of one ALPN's partition for a server (12.17). */
    static List<Object> server(InetSocketAddress remote, Fingerprint fingerprint, String alpn) {
        return Arrays.<Object>asList(remote.getAddress().getHostAddress(), remote.getPort(), fingerprint, alpn);
    }

    synchronized void putToken(List<Object> server, byte[] token) {
        tokens.put(server, token.clone());
    }

    /** The token, removed: a token is used once. */
    synchronized byte[] takeToken(List<Object> server) {
        return tokens.remove(server);
    }

    synchronized boolean hasToken(List<Object> server) {
        return tokens.containsKey(server);
    }

    synchronized byte[] flight(List<Object> server, String joinInputs) {
        byte[] f = flights.get(key(server, joinInputs));
        return f == null ? null : f.clone();
    }

    synchronized void putFlight(List<Object> server, String joinInputs, byte[] flight) {
        flights.put(key(server, joinInputs), flight.clone());
    }

    /** After a mismatch: no 0-RTT to this server until a new QUIC join records again. */
    synchronized void forget(List<Object> server) {
        tokens.remove(server);
        Iterator<List<Object>> it = flights.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().get(0).equals(server)) {
                it.remove();
            }
        }
    }

    synchronized void clear() {
        tokens.clear();
        flights.clear();
    }

    private static List<Object> key(List<Object> server, String joinInputs) {
        return Arrays.<Object>asList(server, joinInputs);
    }

    private static <V> Map<List<Object>, V> lru() {
        return new LinkedHashMap<List<Object>, V>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<List<Object>, V> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
    }
}
