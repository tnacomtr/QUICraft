// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.extract.ExtractorRegistry;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.7: a resource loaded from the classpath is the production path, and it may name only the
 * production-reviewed extractor identifiers. The two fixtures differ in nothing but that:
 * protocol 9002 names {@code mc775.*}, protocol 9003 names the same layouts as
 * {@code primitive.*}. Only the production one loads from the classpath; the synthetic one stays
 * reachable through the explicit test parse path, so renaming a primitive is not review.
 */
class ProductionExtractorAllowlistTest {
    private static final String PRODUCTION = "protocol-9002.json";
    private static final String SYNTHETIC = "protocol-9003.json";

    @Test void productionLoadAcceptsTheReviewedIdentifiers() throws IOException {
        RoutingTable table = RoutingTableLoader.loadResource(9002);
        assertEquals(2, table.schema());
        assertEquals(3, table.routesV2().size());
        assertEquals(setOf("mc775.chunk_xz.v1", "mc775.chunk_list.v1", "mc775.join.v1"),
                new LinkedHashSet<String>(table.extractors().values()));
    }

    @Test void productionLoadRejectsSyntheticIdentifiers() {
        RoutingTableException e = assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.loadResource(9003));
        assertTrue(e.getMessage().contains("13.7"), e.getMessage());
        assertTrue(e.getMessage().contains("not production-approved"), e.getMessage());
        assertTrue(e.getMessage().contains("primitive."), e.getMessage());
    }

    @Test void theTestParsePathAcceptsBothFixturesAndSyntheticIdentifiers() throws IOException {
        RoutingTable synthetic = RoutingTableLoader.parse(bytes(SYNTHETIC));
        assertEquals(2, synthetic.schema());
        assertEquals(setOf("primitive.chunk_xz.v1", "primitive.chunk_list.v1", "primitive.join.v1"),
                new LinkedHashSet<String>(synthetic.extractors().values()));
        // The same fixture, both paths: what differs is the fixture's identifier family, not the
        // resource's shape.
        assertEquals(RoutingTableLoader.parse(bytes(PRODUCTION)).schema(), 2);
        assertEquals(RoutingTableLoader.loadResource(9002).routesV2().size(), 3);
    }

    @Test void theAllowlistIsClosedAndEveryIdentifierKeepsItsDecoding() {
        assertEquals(setOf("mc775.block_position.v1", "mc775.chunk_list.v1", "mc775.chunk_xz.v1",
                "mc775.join.v1", "mc775.section_position.v1", "mc775.varint_xz.v1"),
                ExtractorRegistry.productionIds());
        for (String id : ExtractorRegistry.productionIds()) {
            assertTrue(ExtractorRegistry.isProduction(id), id);
            assertEquals(id, ExtractorRegistry.productionDecoder(id).identifier, id);
        }
        for (String id : new String[] {"primitive.chunk_xz.v1", "primitive.chunk_list.v1",
                "primitive.join.v1", "primitive.block_position.v1", "primitive.section_position.v1",
                "primitive.varint_xz.v1", "test.chunk_xz.v1"}) {
            assertFalse(ExtractorRegistry.isProduction(id), id);
            assertThrows(IllegalArgumentException.class, () -> ExtractorRegistry.productionDecoder(id));
        }
        assertThrows(IllegalArgumentException.class, () -> ExtractorRegistry.productionDecoder("mc775.chunk.v1"));
        assertThrows(IllegalArgumentException.class, () -> ExtractorRegistry.decoder("mc775.chunk.v1"));
        // Same layout, same math: the two families differ only in who may name them.
        byte[] chunk = hex("01ffffffffffffffef");
        assertEquals(ExtractorRegistry.decoder("primitive.chunk_xz.v1").decode(chunk).positions.get(0).chunkZ,
                ExtractorRegistry.productionDecoder("mc775.chunk_xz.v1").decode(chunk).positions.get(0).chunkZ);
    }

    private static Set<String> setOf(String... values) {
        Set<String> set = new LinkedHashSet<String>();
        for (String value : values) set.add(value);
        return set;
    }

    /** The fixture bytes exactly as they are checked in, before any parsing. */
    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = ProductionExtractorAllowlistTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
