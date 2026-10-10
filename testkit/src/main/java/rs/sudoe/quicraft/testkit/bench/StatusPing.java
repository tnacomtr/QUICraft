// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A server-list ping over TCP: handshake (next state 1), status request, status response. Returns
 * the raw JSON, which is where a client reads the QUIC advertisement (docs/protocol.md §1, §4).
 */
final class StatusPing {
    private StatusPing() {}

    static String fetch(InetSocketAddress address, String hostName, int protocolVersion, int timeoutMillis)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(address, timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00);
            writeVarInt(handshake, protocolVersion);
            byte[] host = hostName.getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, host.length);
            handshake.write(host);
            handshake.write(address.getPort() >>> 8);
            handshake.write(address.getPort() & 0xff);
            writeVarInt(handshake, 1);
            OutputStream out = socket.getOutputStream();
            out.write(frame(handshake.toByteArray()));
            out.write(frame(new byte[] {0x00}));
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] packet = new byte[readVarInt(in)];
            in.readFully(packet);
            java.io.ByteArrayInputStream body = new java.io.ByteArrayInputStream(packet);
            if (readVarInt(body) != 0x00) {
                throw new IOException("not a status response");
            }
            byte[] json = new byte[readVarInt(body)];
            if (body.read(json, 0, json.length) != json.length) {
                throw new IOException("truncated status response");
            }
            return new String(json, StandardCharsets.UTF_8);
        }
    }

    private static byte[] frame(byte[] packet) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, packet.length);
        out.writeBytes(packet);
        return out.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7f) != 0) {
            out.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("end of stream in VarInt");
            }
            value |= (b & 0x7f) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt too long");
    }
}
