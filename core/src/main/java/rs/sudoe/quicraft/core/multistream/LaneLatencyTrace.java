// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Opt-in record-stage diagnostics. No frames, payloads, accounts or addresses are logged. */
final class LaneLatencyTrace {
    static final LaneLatencyTrace INSTANCE = new LaneLatencyTrace(
            Boolean.getBoolean("quicraft.v3.latencydiag"), System::nanoTime,
            message -> Logger.getLogger("QUICraft").info(message));

    private final boolean enabled;
    private final LongSupplier clock;
    private final Consumer<String> log;

    LaneLatencyTrace(boolean enabled, LongSupplier clock, Consumer<String> log) {
        this.enabled = enabled;
        this.clock = clock;
        this.log = log;
    }

    void event(Object owner, String stage, Record record) {
        if (!enabled) return;
        StringBuilder line = prefix(owner, stage).append(" stream=").append(record.streamIndex())
                .append(" seq=").append(record.sequence()).append(" kind=").append(record.kind())
                .append(" deps=");
        for (Record.Dependency dep : record.dependencies()) {
            line.append(dep.streamIndex()).append(':').append(dep.sequence()).append(',');
        }
        log.accept(line.toString());
    }

    void route(Object owner, int packetId, SentPacket packet) {
        if (!enabled) return;
        log.accept(prefix(owner, "tx-route").append(" packet=").append(packetId)
                .append(" stream=").append(packet.logicalStream()).append(" seq=").append(packet.sequence())
                .toString());
    }

    private StringBuilder prefix(Object owner, String stage) {
        return new StringBuilder("QUICraft latencydiag t=").append(clock.getAsLong())
                .append(" owner=").append(Integer.toHexString(System.identityHashCode(owner)))
                .append(" stage=").append(stage);
    }
}
