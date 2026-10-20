// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.net.ssl.SSLEngine;
import net.minecraft.network.Connection;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.fabric.client.Transports;

/**
 * Read-only look at the transport a live client Connection actually negotiated, for
 * ExternalArmGameTest: the TLS engine's ALPN, the /3 shard count and the controller's routing
 * table. Nothing here changes the connection.
 */
final class ExternalArmObserver {
    /** What one live connection negotiated; alpn is "" over TCP. */
    record Seen(boolean quic, String alpn, int streamCount, String tableId, String tableDigest, int tableSchema,
            String controllerState) {}

    private ExternalArmObserver() {}

    static void traceChunkLoadFinished(Connection connection) {
        if (!Boolean.getBoolean("quicraft.v3.trace")) return;
        Object controller = staticCall("v3", connection);
        if (controller != null) call(controller, "traceChunkLoadFinished");
    }

    /** Call on the client thread while connected. */
    static Seen observe(Connection connection) {
        boolean quic = Transports.isQuic(connection);
        Object v3Controller = staticCall("v3", connection);
        Object stream = staticCall("quic", connection);
        if (!quic && v3Controller == null) {
            return new Seen(false, "", 1, "", "", 0, "");
        }
        if (v3Controller != null) {
            check(stream instanceof V3Connection, "/3 controller without a V3Connection: " + stream);
            V3Connection v3 = (V3Connection) stream;
            Object quicChannel = read(v3, "connection");
            String alpn = ((SSLEngine) call(quicChannel, "sslEngine")).getApplicationProtocol();
            int shards = v3.sessionContext().shardCount();
            RoutingTable table = (RoutingTable) read(v3Controller, "table");
            check(table != null, "/3 controller has no routing table");
            return new Seen(true, alpn == null ? "" : alpn, shards + 2,
                    "quicraft-routing-p" + table.protocolNumber() + "-s" + table.schema(), table.digestHex(),
                    table.schema(), String.valueOf(call(v3Controller, "state")));
        }
        // /2: the game stream is a PreambleStream over a NettyQuicByteStream on the QUIC connection.
        check(stream != null, "QUIC connection without a recorded stream");
        Object raw = read(stream, "raw");
        Object quicChannel = call(raw, "connection");
        String alpn = ((SSLEngine) call(quicChannel, "sslEngine")).getApplicationProtocol();
        return new Seen(true, alpn == null ? "" : alpn, 1, "", "", 0, "");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException(what);
        }
    }

    private static Object staticCall(String name, Connection connection) {
        try {
            Method method = Transports.class.getDeclaredMethod(name, Connection.class);
            method.setAccessible(true);
            return method.invoke(null, connection);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Object read(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException absent) {
                // search the superclass
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(failure);
            }
        }
        throw new IllegalStateException("no field " + name + " on " + target.getClass().getName());
    }

    /** No-argument method on the class, its superclasses or any (super)interface. */
    static Object call(Object target, String name) {
        try {
            Method method = null;
            for (Class<?> type = target.getClass(); type != null && method == null; type = type.getSuperclass()) {
                try {
                    method = type.getDeclaredMethod(name);
                } catch (NoSuchMethodException absent) {
                    // search the superclass
                }
            }
            Deque<Class<?>> interfaces = new ArrayDeque<>();
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                for (Class<?> iface : type.getInterfaces()) {
                    interfaces.addLast(iface);
                }
            }
            while (method == null && !interfaces.isEmpty()) {
                Class<?> iface = interfaces.removeFirst();
                for (Class<?> parent : iface.getInterfaces()) {
                    interfaces.addLast(parent);
                }
                try {
                    method = iface.getDeclaredMethod(name);
                } catch (NoSuchMethodException absent) {
                    // search the superinterfaces
                }
            }
            if (method == null) {
                throw new NoSuchMethodException(name + " on " + target.getClass().getName());
            }
            method.setAccessible(true);
            return method.invoke(target);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
