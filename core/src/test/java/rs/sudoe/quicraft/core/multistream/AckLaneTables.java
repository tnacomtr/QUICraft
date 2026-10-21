// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.List;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;

/**
 * The synthetic protocol-775 table the ACK lane tests run against (13.21): block_changed_ack (4) and
 * chunk_batch_finished (11) on the ACK lane, block_entity_data (6), forget (37) and level_chunk_with_light (45) on
 * CHUNK with the {@code ack} dependency, a plain CHUNK/lane packet (3) and a few CONTROL and REALTIME ones. The
 * shipped table routes the same ids this way; this synthetic table keeps the ACK tests independent of it.
 */
final class AckLaneTables {
    static final int ORDINARY = 1;
    static final int REALTIME = 2;
    static final int CHUNK_PLAIN = 3;
    static final int BLOCK_ACK = 4;
    static final int BLOCK_ENTITY = 6;
    static final int BATCH_FINISHED = 11;
    static final int VIEW = 21;
    static final int GLOBAL = 22;
    static final int FORGET = 37;
    static final int INSTALL = 45;

    private static final String LIMIT = "{\"maxServerboundRecord\":32768,\"maxServerboundRealtimeRecord\":510,"
            + "\"maxDecodedFootprint\":1048576,\"ordinaryReadAllowance\":5619,"
            + "\"decodeFootprintBound\":\"primitive.raw_copies.v1\"}";

    private AckLaneTables() {
    }

    static String route(String direction, int id, String lane, String dependency, String position) {
        boolean small = direction.equals("serverbound") && lane.equals("control");
        return "{\"state\":\"play\",\"direction\":\"" + direction + "\",\"packet_id\":" + id + ",\"lane\":\"" + lane
                + "\",\"dependency\":\"" + dependency + "\",\"position\":" + position + ",\"epoch_change\":false"
                + (small ? ",\"ordinarySmall\":false" : "") + "}";
    }

    static String json(String... extraRoutes) {
        StringBuilder limits = new StringBuilder("{");
        String[] states = {"handshaking", "status", "login", "configuration", "play"};
        for (int i = 0; i < states.length; i++) {
            limits.append(i > 0 ? "," : "").append('"').append(states[i]).append("\":").append(LIMIT);
        }
        limits.append('}');
        List<String> routes = new ArrayList<String>();
        for (String d : new String[] {"clientbound", "serverbound"}) {
            routes.add(route(d, ORDINARY, "control", "ordinary", "null"));
            routes.add(route(d, REALTIME, "realtime", "lane", "null"));
        }
        routes.add(route("clientbound", VIEW, "control", "view", "null"));
        routes.add(route("clientbound", GLOBAL, "control", "global", "null"));
        routes.add(route("clientbound", CHUNK_PLAIN, "chunk", "lane", "\"xz\""));
        routes.add(route("clientbound", BLOCK_ACK, "ack", "trailing", "null"));
        routes.add(route("clientbound", BATCH_FINISHED, "ack", "trailing", "null"));
        routes.add(route("clientbound", BLOCK_ENTITY, "chunk", "ack", "\"xz\""));
        routes.add(route("clientbound", FORGET, "chunk", "ack", "\"xz\""));
        routes.add(route("clientbound", INSTALL, "chunk", "ack", "\"xz\""));
        for (String extra : extraRoutes) {
            routes.add(extra);
        }
        return "{\"schema\":2,\"protocol\":775,\"default\":\"global\",\"extractors\":{"
                + "\"xz\":\"primitive.chunk_xz.v1\"},\"serverboundLimits\":" + limits
                + ",\"routes\":[" + String.join(",", routes) + "]}";
    }

    static RoutingTable table() {
        return RoutingTableLoader.parse(json());
    }

    /** A chunk-addressed frame: packet id, big-endian x and z, then a label. */
    static byte[] chunkFrame(int id, int x, int z, String label) {
        byte[] text = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.ByteBuffer body = java.nio.ByteBuffer.allocate(8 + text.length);
        body.putInt(x).putInt(z).put(text);
        return SessionRigSupport.frame(id, body.array());
    }

    /** The uncompressed packet bytes a game hands to the session for position extraction. */
    static byte[] chunkUncompressed(int id, int x, int z) {
        return java.nio.ByteBuffer.allocate(9).put((byte) id).putInt(x).putInt(z).array();
    }
}
