// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Stand-in for Mojang's session server, so online-mode logins (and with them Minecraft's
 * AES/CFB8 encryption) run in the testkit without real accounts.
 *
 * <ul>
 *   <li>{@code POST /session/minecraft/join}: the client registers {serverId → profile}.
 *   <li>{@code GET /session/minecraft/hasJoined?username=..&serverId=..}: the server checks it.
 * </ul>
 *
 * Velocity: {@code -Dmojang.sessionserver=http://<host>:8080/session/minecraft/hasJoined}
 * (Velocity appends the query string itself).
 */
public final class MockSessionServer {
    private record Joined(String id, String name) {}

    private final Map<String, Joined> joins = new ConcurrentHashMap<>();

    private MockSessionServer() {}

    /** Serves until the process is killed. */
    public static int run(Args args) throws IOException, InterruptedException {
        int port = args.integer("port", 8080);
        MockSessionServer mock = new MockSessionServer();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 64);
        server.createContext("/session/minecraft/join", mock::join);
        server.createContext("/session/minecraft/hasJoined", mock::hasJoined);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("mock session server listening on :" + port);
        new CountDownLatch(1).await();
        return 0;
    }

    private void join(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            JsonObject body;
            try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                body = JsonParser.parseReader(reader).getAsJsonObject();
            }
            String serverId = body.get("serverId").getAsString();
            String id = body.get("selectedProfile").getAsString();
            String name = body.get("name").getAsString();
            joins.put(key(name, serverId), new Joined(id, name));
            exchange.sendResponseHeaders(204, -1);
        }
    }

    private void hasJoined(HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            Joined joined = joins.remove(key(query.get("username"), query.get("serverId")));
            if (joined == null) {
                // Same as Mojang: 204 means "not joined" and the server rejects the login.
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            JsonObject profile = new JsonObject();
            profile.addProperty("id", joined.id());
            profile.addProperty("name", joined.name());
            profile.add("properties", new JsonArray());
            byte[] bytes = profile.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static String key(String name, String serverId) {
        return name + '\n' + serverId;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
