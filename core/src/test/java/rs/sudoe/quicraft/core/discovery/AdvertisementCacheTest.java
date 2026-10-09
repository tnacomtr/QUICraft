// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

class AdvertisementCacheTest {
    @Test
    void freshForSixtySecondsAndForgottenWhenTheServerStopsAdvertising() throws Exception {
        AtomicLong now = new AtomicLong(0);
        AdvertisementCache cache = new AdvertisementCache(now::get);
        InetSocketAddress server = InetSocketAddress.createUnresolved("192.0.2.1", 25565);
        Advertisement ad = Advertisement.v1(25565, ServerIdentity.generate().fingerprint());
        cache.record(server, Optional.of(ad));
        now.addAndGet(AdvertisementCache.TTL_MILLIS - 1);
        assertEquals(Optional.of(ad), cache.fresh(server));
        now.addAndGet(1);
        assertFalse(cache.fresh(server).isPresent(), "stale after 60 s");

        cache.record(server, Optional.of(ad));
        cache.record(server, Optional.empty());
        assertFalse(cache.fresh(server).isPresent(), "a response without the member removes it");
    }

    @Test
    void staysBounded() throws Exception {
        AdvertisementCache cache = new AdvertisementCache(() -> 0L);
        Advertisement ad = Advertisement.v1(25565, ServerIdentity.generate().fingerprint());
        for (int i = 0; i < AdvertisementCache.MAX_ENTRIES + 10; i++) {
            cache.record(InetSocketAddress.createUnresolved("192.0.2.1", 1000 + i), Optional.of(ad));
        }
        assertFalse(cache.fresh(InetSocketAddress.createUnresolved("192.0.2.1", 1000)).isPresent(), "oldest evicted");
        assertTrue(cache.fresh(InetSocketAddress.createUnresolved("192.0.2.1", 1000 + AdvertisementCache.MAX_ENTRIES + 9))
                .isPresent());
    }
}
