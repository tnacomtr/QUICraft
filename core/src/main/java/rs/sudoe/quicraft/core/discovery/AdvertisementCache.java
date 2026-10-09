// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.discovery;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Advertisements seen in status responses, by the server's resolved TCP address, fresh for
 * 60 seconds (docs/protocol.md §4). Bounded; least recently used entries go first. Thread-safe.
 */
public final class AdvertisementCache {
    static final long TTL_MILLIS = TimeUnit.SECONDS.toMillis(60);
    static final int MAX_ENTRIES = 256;

    private static final class Seen {
        final Advertisement advertisement;
        final long receivedAt;

        Seen(Advertisement advertisement, long receivedAt) {
            this.advertisement = advertisement;
            this.receivedAt = receivedAt;
        }
    }

    private final LongSupplier clock;
    private final Map<InetSocketAddress, Seen> entries = new LinkedHashMap<InetSocketAddress, Seen>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<InetSocketAddress, Seen> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public AdvertisementCache() {
        this(System::currentTimeMillis);
    }

    AdvertisementCache(LongSupplier clock) {
        this.clock = clock;
    }

    /** Records what a status response said; an absent advertisement removes any old one. */
    public synchronized void record(InetSocketAddress server, Optional<Advertisement> advertisement) {
        if (advertisement.isPresent()) {
            entries.put(server, new Seen(advertisement.get(), clock.getAsLong()));
        } else {
            entries.remove(server);
        }
    }

    public synchronized Optional<Advertisement> fresh(InetSocketAddress server) {
        Seen e = entries.get(server);
        if (e == null) {
            return Optional.empty();
        }
        if (clock.getAsLong() - e.receivedAt >= TTL_MILLIS) {
            entries.remove(server);
            return Optional.empty();
        }
        return Optional.of(e.advertisement);
    }
}
