// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import com.google.gson.Gson;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import rs.sudoe.quicraft.bridge.MultistreamSwitch;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.connect.ClientConnector;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.client.Transports;
import rs.sudoe.quicraft.fabric.mixin.ConnectionAccessor;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/** Isolated offline 26.1.2 correctness prerequisite, not a benchmark or security gate. */
public final class RealClientV3GameTest {
    private static final String OFFLINE = "quicraft.multistream.offlineTest";
    private static final String PINS = "pins-v2.json", REBINDS = "rebind-v1.json";
    private static final String FAILURES = "quic-failures.properties";
    private static final long TIMEOUT = 30;

    private RealClientV3GameTest() {}

    public static void smoke(ClientGameTestContext context, String runId) {
        Fixture fixture = new Fixture(context, runId);
        Throwable primary = null;
        try {
            fixture.captureOriginals();
            fixture.publish("STARTED", null);
            fixture.prepare();
            fixture.exercise();
        } catch (Throwable failure) {
            primary = failure;
        }
        // No exception from cleanup may bypass the FAILED publication or replace the primary.
        fixture.attempt("cleanup", fixture::cleanup);
        if (primary == null && !fixture.errors.isEmpty()) primary = fixture.errors.getFirst();
        fixture.finish(primary);
    }

    @FunctionalInterface
    private interface Step { void run() throws Throwable; }

    private record Backup(Path path, boolean existed, byte[] bytes) {
        static Backup read(Path path) throws Exception {
            check(!Files.isSymbolicLink(path), "refuse symlinked properties: " + path);
            boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            return new Backup(path, exists, exists ? Files.readAllBytes(path) : null);
        }

        void restore() throws Exception {
            if (existed) Files.write(path, bytes);
            else Files.deleteIfExists(path);
        }
    }

    /** Only connection/controller/immutable loop are obtained on the render thread. */
    private static final class Live {
        final Connection connection;
        final Object controller;
        final EventLoop loop;
        Channel channel;
        V3Connection transport;
        Object quic, quicLoop, host, session, lanes, table;

        Live(Connection connection, Object controller, EventLoop loop) {
            this.connection = connection;
            this.controller = controller;
            this.loop = loop;
        }

        List<Object> identities() {
            return List.of(connection, controller, loop, channel, transport, quic, quicLoop,
                    host, session, lanes, table);
        }
    }

    private static final class Fixture {
        final ClientGameTestContext context;
        final String runId, nonce = UUID.randomUUID().toString();
        final Instant started = Instant.now();
        final List<Throwable> errors = new ArrayList<>();
        final Map<String, Object> evidence = new LinkedHashMap<>();
        QuicraftClient client;
        Path config;
        RoutingTable table;
        Backup clientFile, serverFile;
        Mode originalMode;
        boolean originalMultistream;
        String originalOffline;
        boolean captured, propertyTouched, modeTouched, multistreamTouched, serverFileTouched;
        boolean stateAttempted, stateCaptured, serverAttempted, connectAttempted, exercised;
        Object state;
        ClientConnector connector;
        ExecutorService worker;
        ScheduledExecutorService timer;
        TestDedicatedServerContext server;
        AutoCloseable join;
        Thread serverThread;
        Live tracked;

        Fixture(ClientGameTestContext context, String runId) {
            this.context = context;
            this.runId = runId;
        }

        /** All checks and backups are read-only. No Hooks calls, including predicate evaluation. */
        void captureOriginals() throws Exception {
            String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            check("26.1.2".equals(version), "fixture requires Minecraft 26.1.2, got " + version);
            check(QuicSupport.hasKeyingMaterialExport(), "native exporter required");
            check(QuicSupport.hasMaxFlowControlWindows(), "native flow-control capability required");
            client = QuicraftClient.get();
            check(client != null, "client initialized");
            synchronized (client) {
                check(read(client, "v3State") == null, "pre-existing /3 state");
                check(read(client, "multistreamConnector") == null, "pre-existing /3 connector");
                check(!client.multistream(), "multistream must initially be off");
                originalMode = client.mode();
                originalMultistream = client.multistream();
            }
            check(context.computeOnClient(c -> c.getConnection() == null && c.level == null),
                    "pre-existing live client/world");
            table = MultistreamSwitch.tableFor(SharedConstants.getProtocolVersion());
            check(table != null && MultistreamSwitch.serves(table), "reviewed schema-2 table required");
            check(table == RoutingRegistry.forProtocol(SharedConstants.getProtocolVersion()).orElseThrow(),
                    "registry table identity");
            config = FabricLoader.getInstance().getConfigDir().resolve("quicraft");
            check(config.equals(read(client, "configDir")), "actual client config directory");
            check(!Files.isSymbolicLink(config), "refuse symlinked config directory");
            checkStores();
            clientFile = Backup.read(config.resolve("client.properties"));
            serverFile = Backup.read(config.resolve("server.properties"));
            originalOffline = System.getProperty(OFFLINE);
            captured = true; // Set only after every backup succeeds.
            evidence.put("gameVersion", version);
            evidence.put("protocol", SharedConstants.getProtocolVersion());
        }

        /** File absence alone does not prove the shared in-memory failure cache is empty. */
        void checkStores() throws Exception {
            if (Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
                check(Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS), "config is not a directory");
                try (var paths = Files.list(config)) {
                    List<String> occupied = paths.map(p -> p.getFileName().toString())
                            .filter(n -> n.startsWith(PINS) || n.startsWith(REBINDS)
                                    || n.startsWith(FAILURES)).toList();
                    check(occupied.isEmpty(), "unexpected stores/prefix entries retained: " + occupied);
                }
            }
            Object cache = read(client, "failures");
            check(cache != null, "missing shared failure cache");
            synchronized (cache) {
                for (String name : List.of("entries", "activationEntries", "unseen")) {
                    check(read(cache, name) instanceof Map<?, ?> map && map.isEmpty(),
                            "shared failure cache is not empty: " + name);
                }
            }
        }

        void prepare() throws Exception {
            propertyTouched = true;
            System.setProperty(OFFLINE, "true");
            modeTouched = true;
            client.setMode(Mode.QUIC_ONLY);
            multistreamTouched = true;
            client.setMultistream(true);
            serverFileTouched = true;
            Files.createDirectories(config);
            Files.writeString(serverFile.path(), "multistream=true\n");
            stateAttempted = true;
            // Do not create, transplant or substitute a connector. Capture partially published state
            // even if the normal lazy path fails. An unpublished allocation is NOT presumed closed.
            try {
                client.multistreamConnector();
            } finally {
                synchronized (client) {
                    state = read(client, "v3State");
                    connector = (ClientConnector) read(client, "multistreamConnector");
                    if (state != null) {
                        worker = (ExecutorService) read(state, "worker");
                        timer = (ScheduledExecutorService) read(state, "timer");
                        stateCaptured = worker != null && timer != null;
                    }
                }
            }
            check(stateCaptured && connector != null, "normal lazy /3 connector/state unavailable");
        }

        void exercise() throws Exception {
            Properties properties = new Properties();
            properties.setProperty("server-port", Integer.toString(GameTests.freePort()));
            properties.setProperty("online-mode", "false");
            properties.setProperty("level-type", "minecraft:flat");
            properties.setProperty("generate-structures", "false");
            properties.setProperty("spawn-protection", "0");
            properties.setProperty("view-distance", "4");
            properties.setProperty("simulation-distance", "4");
            properties.setProperty("server-ip", "127.0.0.1");
            serverAttempted = true;
            server = context.worldBuilder().createServer(properties);
            serverThread = server.computeOnServer(s -> s.getRunningThread());
            check(serverThread != null && serverThread.isAlive(), "capture live dedicated server thread");
            evidence.put("boundAddresses", server.computeOnServer(s -> {
                List<?> channels = (List<?>) read(s.getConnection(), "channels");
                List<String> tcp = new ArrayList<>();
                synchronized (channels) {
                    for (Object entry : channels) {
                        Object address = ((ChannelFuture) entry).channel().localAddress();
                        requireLoopback(address, "TCP");
                        tcp.add(address.toString());
                    }
                }
                check(!tcp.isEmpty(), "no actual TCP listener observed");
                Object current;
                try {
                    Field field = ServerQuic.class.getDeclaredField("current");
                    field.setAccessible(true);
                    current = field.get(null);
                } catch (ReflectiveOperationException failure) {
                    throw unchecked(failure);
                }
                check(current != null, "no actual QUIC listener observed");
                Object udp = call(read(current, "server"), "localAddress");
                requireLoopback(udp, "QUIC");
                return Map.of("tcp", tcp, "quic", udp.toString());
            }));
            Advertisement advertisement = server.computeOnServer(s -> ServerQuic.advertisement());
            check(advertisement != null && "quicraft/3".equals(advertisement.alpn()),
                    "dedicated server must advertise /3");
            evidence.put("port", advertisement.port());
            connectAttempted = true;
            join = server.connect();
            check(join != null, "connect returned no context");
            track(); // Save actual close resources before chunks or any success-only assertions.
            check(tracked != null && tracked.quic != null, "actual join resources unavailable");
            GameTests.waitForChunks(join);
            Live before = sample("before");
            context.waitTicks(200);
            Live after = sample("after");
            List<Object> a = before.identities(), b = after.identities();
            for (int i = 0; i < a.size(); i++) check(a.get(i) == b.get(i), "live identity drift: " + i);
            check(tracked.connection == before.connection && tracked.quic == before.quic,
                    "tracked transport differs from rendered join");
            evidence.put("rendered", true);
            evidence.put("sustainedTicks", 200);
            exercised = true;
        }

        Live published() {
            return context.computeOnClient(c -> {
                if (c.getConnection() == null) return null;
                Connection connection = c.getConnection().getConnection();
                Object controller = transportEntry("v3", connection);
                EventLoop loop = controller == null ? null : (EventLoop) read(controller, "loop");
                return new Live(connection, controller, loop);
            });
        }

        /** The only deep capture path, also used to recover a thrown connect(). */
        Live fill(Live published) throws Exception {
            check(published.loop != null, "no published /3 owner loop");
            return onLoop(published.loop, () -> {
                // Never expose a partially filled object if the bounded owner-loop wait times out.
                Live live = new Live(published.connection, published.controller, published.loop);
                live.channel = ((ConnectionAccessor) live.connection).quicraft$channel();
                Object transport = transportEntry("quic", live.connection);
                if (transport instanceof V3Connection v3) {
                    live.transport = v3;
                    live.quic = read(v3, "connection");
                    live.quicLoop = call(live.quic, "eventLoop");
                    live.host = read(live.quicLoop, "host");
                    live.session = v3.session();
                }
                live.table = read(live.controller, "table");
                live.lanes = read(live.controller, "lanes");
                return live;
            });
        }

        void track() throws Exception {
            if (tracked == null) {
                for (int tick = 0; tick < 100; tick++) {
                    Live live = published();
                    if (live != null) {
                        tracked = live; // Retain the partial bundle even if fill() fails.
                        break;
                    }
                    context.waitTick();
                }
            }
            if (tracked != null) tracked = fill(tracked);
        }

        Live sample(String label) throws Exception {
            Live published = published();
            check(published != null && published.controller != null, label + ": live /3 controller required");
            Live live = fill(published);
            CompletableFuture<Boolean> roundtrip = new CompletableFuture<>();
            Map<String, Object> result = onLoop(live.loop, () -> {
                check(live.connection.isConnected() && live.channel.isOpen() && live.channel.isActive(),
                        label + ": game connection must remain live");
                check(live.channel.eventLoop() == live.loop, "game channel owner mismatch");
                check(read(live.controller, "channel") == live.channel, "controller channel mismatch");
                check(read(live.controller, "v3") == live.transport, "controller transport mismatch");
                check(live.table == table && live.lanes != null && live.session != null,
                        "active lanes/session/registry table required");
                check("ACTIVE".equals(String.valueOf(call(live.controller, "state"))), "controller not ACTIVE");
                check(live.transport.inEventLoop(), "transport owner mismatch");
                check(Boolean.TRUE.equals(call(live.quicLoop, "inEventLoop")), "QUIC owner mismatch");
                boolean actualHost = false;
                for (Field field : live.host.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    if (field.get(live.host) == live.loop) actualHost = true;
                }
                check(actualHost, "host does not delegate to actual game loop");
                check(Boolean.TRUE.equals(call(live.quic, "isOpen"))
                        && Boolean.TRUE.equals(call(live.quic, "isActive")), "QUIC not live");
                String alpn = ((SSLEngine) call(live.quic, "sslEngine")).getApplicationProtocol();
                check("quicraft/3".equals(alpn), "actual TLS engine did not negotiate /3: " + alpn);
                int shards = live.transport.sessionContext().shardCount();
                check(shards > 1, "negotiated shard count must exceed one");
                live.transport.execute(() -> roundtrip.complete(live.loop.inEventLoop()));
                return Map.<String, Object>of("alpn", alpn, "shardCount", shards, "state", "ACTIVE");
            });
            // execute() is asynchronous. Observe its completion off-loop, never assume inline.
            check(!live.loop.inEventLoop(), "gametest must not block transport owner");
            check(await(roundtrip), "transport callback ran off owner loop");
            evidence.put(label, result);
            return live;
        }

        void cleanup() throws Throwable {
            if (!captured || !(propertyTouched || modeTouched || multistreamTouched || serverFileTouched)) return;
            boolean transportClosed = !connectAttempted;
            if (connectAttempted) {
                // connect() may throw before returning its context. Recover what was published, but
                // never equate missing publication with absence of an outstanding attempt.
                if (tracked == null || tracked.quic == null) attempt("recover join resources", this::track);
                if (join != null) attempt("close connection context", join::close);
                // Always disconnect the fixture join, even when no context or close evidence exists.
                attempt("disconnect fixture client", () -> context.runOnClient(c -> c.disconnectWithSavingScreen()));
                if (tracked != null) {
                    Live saved = tracked;
                    if (saved.channel != null) attempt("close saved game channel", () -> saved.channel.close());
                    if (saved.transport != null) attempt("close saved transport", saved.transport::close);
                    transportClosed = attempt("prove transport closure", () -> {
                        check(saved.channel != null && saved.quic != null && saved.loop != null,
                                "unknown partial attempt: cannot prove closure; retain state/configuration");
                        check(!saved.loop.inEventLoop(), "cannot wait on owner loop");
                        await(saved.channel.closeFuture());
                        await((Future<?>) call(saved.quic, "closeFuture"));
                        onLoop(saved.loop, () -> null);
                        check(context.computeOnClient(c -> c.getConnection() == null && c.level == null),
                                "client connection remains after closure");
                    });
                } else {
                    attempt("unpublished pending join", () -> { throw new AssertionError(
                            "no actual close futures: pending attempt closure UNKNOWN; retain workers/config"); });
                }
            }
            // Always attempt server.close(), independently of capture/connection failures.
            boolean serverClosed = !serverAttempted;
            if (server != null) {
                boolean closed = attempt("close dedicated server", server::close);
                boolean dead = attempt("prove dedicated server thread dead", () -> {
                    check(serverThread != null, "server thread identity was not captured while alive");
                    check(!serverThread.isAlive(), "dedicated server thread survived context.close()");
                });
                serverClosed = closed && dead;
            } else if (serverAttempted) {
                attempt("unpublished server", () -> { throw new AssertionError("partial server startup UNKNOWN"); });
            }
            evidence.put("transportClosureProven", transportClosed);
            evidence.put("serverClosureProven", serverClosed);
            if (!transportClosed || !serverClosed) return;
            if (stateAttempted) {
                if (!attempt("prove owned state", () -> check(stateCaptured,
                        "lazy creation failed without fully published workers; retain configuration"))) return;
                if (!attempt("terminate timer", () -> {
                    timer.shutdownNow();
                    check(timer.awaitTermination(TIMEOUT, TimeUnit.SECONDS), "timer did not terminate");
                })) return;
                if (!attempt("drain and terminate worker", () -> {
                    Thread thread = await(worker.submit(() -> Thread.currentThread()));
                    call(state, "close");
                    check(worker.awaitTermination(TIMEOUT, TimeUnit.SECONDS), "worker did not terminate");
                    thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT));
                    check(worker.isTerminated() && !thread.isAlive(), "worker thread still alive");
                })) return;
                if (!attempt("restore owned singleton fields", () -> {
                    synchronized (client) {
                        check(read(client, "v3State") == state
                                && read(client, "multistreamConnector") == connector, "singleton ownership drift");
                        write(client, "multistreamConnector", null);
                        write(client, "v3State", null);
                    }
                })) return;
            }
            // No store restoration/deletion is permitted, including on unexpected cache output.
            attempt("protect stores", this::checkStores);
            // Setters write client.properties, so exact bytes/existence must be restored AFTER them.
            if (modeTouched) attempt("restore mode", () -> client.setMode(originalMode));
            if (multistreamTouched) attempt("restore multistream", () -> client.setMultistream(originalMultistream));
            if (modeTouched || multistreamTouched) attempt("restore client properties", clientFile::restore);
            if (serverFileTouched) attempt("restore server properties", serverFile::restore);
            if (propertyTouched) attempt("restore offline property last", () -> {
                if (originalOffline == null) System.clearProperty(OFFLINE);
                else System.setProperty(OFFLINE, originalOffline);
            });
            evidence.put("cleanupFinished", true);
        }

        boolean attempt(String label, Step step) {
            try {
                step.run();
                return true;
            } catch (Throwable failure) {
                errors.add(new AssertionError(label, failure));
                return false;
            }
        }

        void finish(Throwable primary) {
            boolean complete = primary == null && errors.isEmpty() && exercised
                    && Boolean.TRUE.equals(evidence.get("cleanupFinished"));
            if (!complete && primary == null) primary = new AssertionError("fixture incomplete: " + evidence);
            if (primary != null) {
                for (Throwable error : errors) if (error != primary) primary.addSuppressed(error);
            }
            // Publish exactly one authoritative final artifact, atomically. No secondary summary
            // write can fail after a COMPLETED publication. Publication failure aborts continuation.
            try {
                publish(complete ? "COMPLETED" : "FAILED", primary);
            } catch (Throwable failure) {
                if (primary == null) primary = failure;
                else primary.addSuppressed(failure);
                // Best effort FAILED publication, preserving the actual primary exception.
                try { publish("FAILED", primary); }
                catch (Throwable again) { primary.addSuppressed(again); }
            }
            if (primary != null) throw unchecked(primary);
        }

        void publish(String status, Throwable primary) throws Exception {
            Map<String, Object> report = new LinkedHashMap<>(evidence);
            report.put("status", status);
            report.put("runId", runId);
            report.put("fixtureNonce", nonce);
            report.put("started", started.toString());
            report.put("published", Instant.now().toString());
            report.put("errors", errors.stream().map(RealClientV3GameTest::describe).toList());
            if (primary != null) report.put("primary", describe(primary));
            Path directory = outputDir();
            Files.createDirectories(directory);
            String suffix = "STARTED".equals(status) ? "started" : "final";
            Path target = directory.resolve("realclient-v3-" + nonce + "-" + suffix + ".json");
            Path pending = directory.resolve(target.getFileName() + ".pending");
            Files.writeString(pending, new Gson().toJson(report) + "\n");
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path outputDir() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path path = start; path != null; path = path.getParent()) {
            if (Files.exists(path.resolve("settings.gradle.kts"))) return path.resolve("fabric-26x/build/causal-send");
        }
        throw new AssertionError("no repository ancestor: " + start);
    }

    private static void requireLoopback(Object address, String transport) {
        check(address instanceof InetSocketAddress socket && socket.getAddress() != null
                && socket.getAddress().isLoopbackAddress(), transport + " listener is not loopback: " + address);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.get(TIMEOUT, TimeUnit.SECONDS);
    }

    private static <T> T onLoop(EventLoop loop, Callable<T> task) throws Exception {
        check(!loop.inEventLoop(), "cannot block the owner loop");
        return await(loop.submit(() -> {
            check(loop.inEventLoop(), "snapshot task is not on owner loop");
            return task.call();
        }));
    }

    private static Object transportEntry(String name, Connection connection) {
        try {
            Method method = Transports.class.getDeclaredMethod(name, Connection.class);
            method.setAccessible(true);
            return method.invoke(null, connection);
        } catch (ReflectiveOperationException failure) {
            throw unchecked(failure);
        }
    }

    private static Object read(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw unchecked(failure);
        }
    }

    private static void write(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Only no-argument missing accessors/relocated transport methods need reflective dispatch. */
    private static Object call(Object target, String name) {
        try {
            Method method = null;
            for (Class<?> type = target.getClass(); type != null && method == null; type = type.getSuperclass()) {
                try { method = type.getDeclaredMethod(name); }
                catch (NoSuchMethodException absent) { /* search superclass */ }
            }
            // Some relocated transport methods (for example EventLoop.inEventLoop()) are interface
            // defaults: they exist only on the interfaces a class (or its parents) implement.
            Deque<Class<?>> interfaces = new ArrayDeque<>();
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                for (Class<?> iface : type.getInterfaces()) interfaces.addLast(iface);
            }
            while (method == null && !interfaces.isEmpty()) {
                Class<?> iface = interfaces.removeFirst();
                for (Class<?> parent : iface.getInterfaces()) interfaces.addLast(parent);
                try { method = iface.getDeclaredMethod(name); }
                catch (NoSuchMethodException absent) { /* search superinterfaces */ }
            }
            if (method == null) throw new NoSuchMethodException(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (InvocationTargetException failure) {
            throw unchecked(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw unchecked(failure);
        }
    }

    private static String describe(Throwable failure) {
        java.io.StringWriter text = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(text));
        return text.toString();
    }

    private static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException(failure);
    }
}
