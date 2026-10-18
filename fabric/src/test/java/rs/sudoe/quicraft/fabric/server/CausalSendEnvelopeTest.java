// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.AckJoinTracker;
import rs.sudoe.quicraft.core.multistream.MultistreamSession.CausalScope;
import rs.sudoe.quicraft.core.multistream.MultistreamSession.UpdateToken;

/**
 * The envelope's identity and snapshots are fixed at creation (U3-W2a). Every list is an immutable
 * copy of what the producer recorded; the observed ACK coalescing values and wire-order bundle
 * children are kept exactly.
 */
class CausalSendEnvelopeTest {
    private static CausalSendEnvelope envelope(long sendId, long epoch,
            CausalSendEnvelope.ProducerSnapshot producer, CausalSendEnvelope.BundleMetadata bundle) {
        return new CausalSendEnvelope(sendId, "connection", "owner", epoch, producer, bundle);
    }

    @Test
    void producerSnapshotCopiesScopeAndTokenLists() {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope scope = tracker.openScope();
        UpdateToken token = tracker.registerUpdate(scope);
        List<CausalScope> scopes = new ArrayList<>(List.of(scope));
        List<UpdateToken> tokens = new ArrayList<>(List.of(token));

        CausalSendEnvelope.ProducerSnapshot producer =
                new CausalSendEnvelope.ProducerSnapshot(scopes, tokens, null, null);
        scopes.clear();
        tokens.clear();

        assertEquals(List.of(scope), producer.scopes(), "scope handles are kept, the source list is not");
        assertEquals(List.of(token), producer.tokens(), "update tokens are kept, the source list is not");
        assertThrows(UnsupportedOperationException.class, () -> producer.scopes().add(scope));
        assertThrows(UnsupportedOperationException.class, () -> producer.tokens().add(token));
    }

    @Test
    void ackOperationKeepsTheObservedCoalescingValues() {
        List<Integer> covered = new ArrayList<>(List.of(4, 0, 5, 0));
        CausalSendEnvelope.AckOperation ack = new CausalSendEnvelope.AckOperation(7, 5, covered);
        covered.clear();

        assertEquals(7, ack.ackId());
        assertEquals(5, ack.sequence());
        assertEquals(List.of(4, 0, 5, 0), ack.coveredSequences(), "coalesced coverage keeps order and duplicates");
        assertThrows(UnsupportedOperationException.class, () -> ack.coveredSequences().add(1));
    }

    @Test
    void batchOperationKeepsBoundaryAndChildFacts() {
        CausalSendEnvelope.BatchOperation batch = new CausalSendEnvelope.BatchOperation(
                43, CausalSendEnvelope.BatchKind.INSTALL, 3, 37);

        assertEquals(43, batch.batchId());
        assertEquals(CausalSendEnvelope.BatchKind.INSTALL, batch.kind());
        assertEquals(3, batch.index());
        assertEquals(37, batch.expected());
    }

    @Test
    void bundleChildrenAreOrderedAndImmutable() {
        Object first = new Object();
        Object second = new Object();
        CausalSendEnvelope.BundleMetadata bundle = CausalSendEnvelope.BundleMetadata.of(List.of(
                new CausalSendEnvelope.BundleChild(0, first),
                new CausalSendEnvelope.BundleChild(1, second)));

        assertTrue(bundle.bundle());
        assertEquals(2, bundle.children().size());
        assertSame(first, bundle.children().get(0).packet());
        assertSame(second, bundle.children().get(1).packet());
        assertEquals(1, bundle.children().get(1).index());
        assertThrows(UnsupportedOperationException.class,
                () -> bundle.children().add(new CausalSendEnvelope.BundleChild(2, new Object())));
    }

    @Test
    void bundleMetadataRejectsReorderingAndChildrenOnANonBundle() {
        assertThrows(IllegalArgumentException.class, () -> CausalSendEnvelope.BundleMetadata.of(List.of(
                new CausalSendEnvelope.BundleChild(1, new Object()))));
        assertThrows(IllegalArgumentException.class, () -> new CausalSendEnvelope.BundleMetadata(
                false, List.of(new CausalSendEnvelope.BundleChild(0, new Object()))));
        assertEquals(List.of(), CausalSendEnvelope.BundleMetadata.NONE.children());
    }

    @Test
    void envelopeRejectsMissingIdentityOrSnapshot() {
        CausalSendEnvelope.ProducerSnapshot producer = CausalSendEnvelope.ProducerSnapshot.EMPTY;
        CausalSendEnvelope.BundleMetadata bundle = CausalSendEnvelope.BundleMetadata.NONE;

        assertThrows(IllegalArgumentException.class,
                () -> new CausalSendEnvelope(0, "connection", null, 0, producer, bundle));
        assertThrows(IllegalArgumentException.class,
                () -> new CausalSendEnvelope(1, "connection", null, -1, producer, bundle));
        assertThrows(NullPointerException.class,
                () -> new CausalSendEnvelope(1, null, null, 0, producer, bundle));
        assertThrows(NullPointerException.class,
                () -> new CausalSendEnvelope(1, "connection", null, 0, null, bundle));
        assertThrows(NullPointerException.class,
                () -> new CausalSendEnvelope(1, "connection", null, 0, producer, null));
    }

    @Test
    void toStringDoesNotCarryPacketIdentityOrPayloads() {
        CausalSendEnvelope envelope = envelope(9, 4, CausalSendEnvelope.ProducerSnapshot.EMPTY,
                CausalSendEnvelope.BundleMetadata.NONE);

        assertEquals("CausalSendEnvelope[sendId=9, epoch=4, bundle=false, children=0]", envelope.toString());
        assertEquals("connection", envelope.connection());
        assertEquals("owner", envelope.sessionOwner());
    }
}
