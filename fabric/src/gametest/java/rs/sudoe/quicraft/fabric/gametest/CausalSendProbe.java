// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import rs.sudoe.quicraft.fabric.mixin.ConnectionAccessor;
import rs.sudoe.quicraft.fabric.server.CausalSendContext;
import rs.sudoe.quicraft.fabric.server.CausalSendEnvelope;

/** Driver-owned tokens and independent production-allocation/write observations. */
public final class CausalSendProbe {
    private static final ThreadLocal<Row> INVOCATION = new ThreadLocal<>();
    private static final ThreadLocal<Row> OBSERVATION = new ThreadLocal<>();
    private static final Map<String, Row> ROWS = new LinkedHashMap<>();
    private static final IdentityHashMap<Packet<?>, Row> WITNESSES = new IdentityHashMap<>();

    private CausalSendProbe() {}

    public static synchronized Row register(String id, String category, Connection connection, Packet<?> packet) {
        if (ROWS.containsKey(id)) throw new AssertionError("duplicate invocation id " + id);
        Row prior = WITNESSES.get(packet);
        if (prior != null && !prior.retired) throw new AssertionError("ambiguous outstanding witness packet " + id);
        Row row = new Row(id, category, connection, packet, connection.getPacketListener(), Thread.currentThread());
        ROWS.put(id, row);
        WITNESSES.put(packet, row);
        return row;
    }

    /** Every controlled send enters only through this driver, before calling public Connection.send. */
    public static void invoke(String id, String category, Connection connection, Packet<?> packet, Runnable send) {
        Row row = register(id, category, connection, packet);
        Row prior = INVOCATION.get();
        INVOCATION.set(row);
        try {
            send.run();
        } catch (Throwable error) {
            row.error("send threw: " + error);
            throw error;
        } finally {
            if (prior == null) INVOCATION.remove(); else INVOCATION.set(prior);
            row.producerReturned = true;
        }
    }

    /** Called only by the RETURN observer on the real project allocator. */
    public static void allocated(CausalSendContext context, CausalSendEnvelope envelope) {
        Row row = INVOCATION.get();
        // Ordinary game traffic is deliberately untracked; only driver-owned calls have tokens.
        if (row == null) return;
        synchronized (row) {
            row.birthCount++;
            if (row.connection != context.connection() || envelope.connection() != row.connection)
                row.error("allocation connection identity mismatch");
            if (row.birthCount > 1) {
                row.error("duplicate production allocation");
            } else {
                row.context = context;
                row.envelope = envelope;
                row.allocationThread = Thread.currentThread();
            }
            if (Thread.currentThread() != row.producerThread) row.error("allocation did not run on the driver producer thread");
            if (envelope.sessionOwner() != row.owner) row.error("session owner differs from pre-send owner");
            if (envelope.epoch() != 0) row.error("expected epoch 0, got " + envelope.epoch());
            if (!envelope.producer().scopes().isEmpty() || !envelope.producer().tokens().isEmpty()
                    || envelope.producer().ack() != null || envelope.producer().batch() != null)
                row.error("expected empty producer snapshot");
            if (envelope.bundle().bundle() || !envelope.bundle().children().isEmpty())
                row.error("expected unbundled envelope");
        }
    }

    static Row witness(Channel channel, Object message) {
        if (!(message instanceof Packet<?> packet)) return null;
        synchronized (CausalSendProbe.class) {
            return WITNESSES.get(packet);
        }
    }

    static Row observing() { return OBSERVATION.get(); }
    static Row beginObservation(Row row) { Row prior = OBSERVATION.get(); OBSERVATION.set(row); return prior; }
    static void restoreObservation(Row prior) { if (prior == null) OBSERVATION.remove(); else OBSERVATION.set(prior); }

    static void terminal(Row row, Channel channel, CausalSendEnvelope actual, Thread writer) {
        synchronized (row) {
            row.terminalCount++;
            if (row.terminalCount > 1) row.error("duplicate terminal packet write");
            row.writerThread = writer;
            row.writeChannel = channel;
            row.writerInEventLoop = channel.eventLoop().inEventLoop();
            row.terminalEnvelope = actual;
            if (row.envelope == null) row.error("write preceded production allocation observation");
            else if (actual != row.envelope) row.error("terminal envelope is not the independently observed allocation");
            if (actual == null) row.error("terminal write had no actual production envelope");
            else if (row.connection != actual.connection()) row.error("terminal envelope connection mismatch");
            CausalSendContext attached = CausalSendContext.attached(channel);
            if (attached != row.context) row.error("write channel is not attached to the recorded birth context");
            try {
                if (((ConnectionAccessor) row.connection).quicraft$channel() != channel)
                    row.error("game Connection channel getter differs from write channel");
            } catch (Throwable error) {
                row.error("could not verify game Connection channel getter: " + error);
            }
        }
    }

    static void encoded(Row row, int bytes) {
        synchronized (row) {
            row.bufferCount++;
            row.encodedBytes += bytes;
            if (OBSERVATION.get() != row) row.error("encoded-byte observation has no matching scoped token");
            if (Thread.currentThread() != row.writerThread) row.error("encoded-byte observation is not on terminal writer thread");
            if (row.context != null && CausalSendContext.currentEnvelope(row.channel) != row.envelope)
                row.error("encoded-byte boundary saw the wrong production envelope");
        }
    }

    /** Caller invokes only after validation and its explicit drain/barrier has completed. */
    public static synchronized void retire(Row row) {
        if (!row.validated) throw new AssertionError("cannot retire unvalidated invocation " + row.id);
        if (WITNESSES.get(row.packet) != row) throw new AssertionError("witness binding changed before retirement " + row.id);
        WITNESSES.remove(row.packet);
        row.retired = true;
    }
    public static synchronized List<Row> rows() { return List.copyOf(ROWS.values()); }
    public static synchronized void reset() { ROWS.clear(); WITNESSES.clear(); INVOCATION.remove(); OBSERVATION.remove(); }

    public static final class Row {
        public final String id, category;
        public final Connection connection;
        public final Packet<?> packet;
        public final Object owner;
        public final Thread producerThread;
        public final List<String> errors = new ArrayList<>();
        public volatile CausalSendContext context;
        public volatile CausalSendEnvelope envelope, terminalEnvelope;
        public volatile io.netty.channel.Channel channel;
        public volatile Channel writeChannel;
        public volatile Thread allocationThread, writerThread;
        public volatile int birthCount, terminalCount, bufferCount, encodedBytes;
        public volatile boolean producerReturned, writerInEventLoop, producerClean, writerClean, sameLiveContextChecked;
        public volatile boolean validated, retired;
        private Row(String id, String category, Connection connection, Packet<?> packet, Object owner, Thread producer) {
            this.id = id; this.category = category; this.connection = connection; this.packet = packet;
            this.owner = owner; this.producerThread = producer;
        }
        public synchronized void error(String text) { errors.add(text); }
        public synchronized void validate() {
            if (!errors.isEmpty()) throw new AssertionError(id + " errors=" + errors);
            if (!producerReturned) throw new AssertionError(id + " public send did not return");
            if (birthCount != 1) throw new AssertionError(id + " birthCount=" + birthCount);
            if (context == null || envelope == null || allocationThread != producerThread)
                throw new AssertionError(id + " missing actual allocation on producer thread");
            if (terminalCount != 1 || terminalEnvelope != envelope || writeChannel == null)
                throw new AssertionError(id + " terminalCount=" + terminalCount + " identity=" + (terminalEnvelope == envelope));
            if (!writerInEventLoop) throw new AssertionError(id + " writer was not on the channel event loop");
            if (CausalSendContext.attached(writeChannel) != context) throw new AssertionError(id + " write channel context differs from birth context");
            try {
                if (((ConnectionAccessor) connection).quicraft$channel() != writeChannel)
                    throw new AssertionError(id + " game Connection channel differs from write channel");
            } catch (ClassCastException error) { throw new AssertionError(id + " connection accessor unavailable", error); }
            if (encodedBytes <= 0) throw new AssertionError(id + " encodedBytes=" + encodedBytes);
            validated = true;
        }
        public synchronized Map<String, Object> json() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id); out.put("category", category); out.put("status", validated && errors.isEmpty() ? "PASS" : "FAIL");
            out.put("birthCount", birthCount); out.put("terminalCount", terminalCount);
            out.put("sendId", envelope == null ? null : envelope.sendId());
            out.put("envelopeIdentity", envelope == null ? 0 : System.identityHashCode(envelope));
            out.put("terminalEnvelopeIdentity", terminalEnvelope == null ? 0 : System.identityHashCode(terminalEnvelope));
            out.put("contextIdentity", context == null ? 0 : System.identityHashCode(context));
            out.put("connectionIdentity", System.identityHashCode(connection));
            out.put("producerThread", producerThread.getName()); out.put("writerThread", writerThread == null ? null : writerThread.getName());
            out.put("producerReturned", producerReturned); out.put("writerInEventLoop", writerInEventLoop);
            out.put("producerClean", producerClean); out.put("writerClean", writerClean);
            out.put("sameLiveContextChecked", sameLiveContextChecked);
            out.put("encodedBytes", encodedBytes); out.put("bufferCount", bufferCount); out.put("errors", List.copyOf(errors));
            return out;
        }
    }
}
