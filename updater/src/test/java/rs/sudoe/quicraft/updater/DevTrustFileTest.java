// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The development trust file ({@code updater/dev-trust/updater-trust.json}) is built from the
 * published RFC 8032 test keys. It must parse as a valid trust file and hold exactly those keys.
 * It is never released: {@code checkReleaseTrust} refuses it.
 */
class DevTrustFileTest {

    private static final Path DEV_TRUST = Paths.get("dev-trust", "updater-trust.json");

    @Test
    void devTrustFileParsesAndHoldsTheTestKeys() throws Exception {
        UpdateDocuments.Trust trust = DocumentParser.parseTrust(Files.readAllBytes(DEV_TRUST));

        assertEquals(1, trust.epoch());
        assertEquals(0L, trust.revocationsSeqFloor());
        assertEquals(0L, trust.manifestSeqFloor());
        List<UpdateDocuments.TrustedKey> keys = trust.keys();
        assertEquals(2, keys.size());
        assertEquals(TestKeys.PUB_1, keys.get(0).pub());
        assertEquals(TestKeys.KID_1, keys.get(0).kid());
        assertEquals(List.of("release"), keys.get(0).roles());
        assertEquals(TestKeys.PUB_2, keys.get(1).pub());
        assertEquals(TestKeys.KID_2, keys.get(1).kid());
        assertEquals(List.of("release", "revocation"), keys.get(1).roles());
    }
}
