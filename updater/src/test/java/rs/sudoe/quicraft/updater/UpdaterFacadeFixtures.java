// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import rs.sudoe.quicraft.updater.UpdaterRuntime.Seams;

/**
 * Shared fixtures for the {@link UpdaterRuntime} tests. Only TEST keys and the existing signed fixtures
 * are used; nothing touches the network.
 */
final class UpdaterFacadeFixtures {

    static final String PLATFORM = TransactionFixtures.PLATFORM;
    static final String TARGET = TransactionFixtures.TARGET;
    static final String RUNNING = TransactionFixtures.RUNNING_VERSION;
    static final String NEWER = TransactionFixtures.NEW_VERSION;

    private UpdaterFacadeFixtures() {
    }

    /** Records every line; {@code failWith} makes every call throw instead. */
    static final class RecordingLog implements UpdaterLog {
        final List<String> lines = new CopyOnWriteArrayList<>();
        volatile RuntimeException failWith;

        private void add(String level, String message) {
            if (failWith != null) {
                throw failWith;
            }
            lines.add(level + ": " + message);
        }

        @Override
        public void debug(String message) {
            add("DEBUG", message);
        }

        @Override
        public void info(String message) {
            add("INFO", message);
        }

        @Override
        public void warn(String message) {
            add("WARN", message);
        }

        List<String> warnings() {
            return lines.stream().filter(l -> l.startsWith("WARN: ")).toList();
        }

        boolean contains(String part) {
            return lines.stream().anyMatch(l -> l.contains(part));
        }
    }

    /** A discovery that returns a fixed live jar or fails as scripted. */
    static final class FixedDiscovery implements LiveJarDiscovery {
        LiveJar result;
        IOException ioFailure;
        RuntimeException runtimeFailure;

        @Override
        public LiveJar discover() throws IOException, UpdateRejectedException {
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            if (ioFailure != null) {
                throw ioFailure;
            }
            return result;
        }
    }

    /** A platform adapter stand-in. */
    static final class FakePlatform implements UpdaterPlatform {
        String platform = PLATFORM;
        String target = TARGET;
        Path updateDir;
        Path configFile;
        boolean existedBeforeStart;
        LiveJarDiscovery discovery;
        HealthSignal health = healthy(true);
        final RecordingLog log = new RecordingLog();
        final List<String> notices = new CopyOnWriteArrayList<>();
        volatile RuntimeException noticeFailure;

        @Override
        public String platform() {
            return platform;
        }

        @Override
        public String gameTarget() {
            return target;
        }

        @Override
        public Path updateDir() {
            return updateDir;
        }

        @Override
        public Path configFile() {
            return configFile;
        }

        @Override
        public boolean updateDirExistedBeforeStart() {
            return existedBeforeStart;
        }

        @Override
        public LiveJarDiscovery discovery() {
            return discovery;
        }

        @Override
        public HealthSignal health() {
            return health;
        }

        @Override
        public UpdaterLog log() {
            return log;
        }

        @Override
        public void notifyAdmins(String message) {
            if (noticeFailure != null) {
                throw noticeFailure;
            }
            notices.add(message);
        }
    }

    /** A health signal that answers at once. */
    static HealthSignal healthy(boolean verdict) {
        return new HealthSignal() {
            @Override
            public CompletionStage<Boolean> awaitHealthy(Duration timeout) {
                return CompletableFuture.completedFuture(verdict);
            }
        };
    }

    /** A thread factory that counts scheduled tasks, for the scheduler tests. */
    static Function<ThreadFactory, ScheduledExecutorService> countingExecutors(List<Runnable> scheduled) {
        return factory -> new java.util.concurrent.ScheduledThreadPoolExecutor(1, factory) {
            @Override
            public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                    long initialDelay, long period, java.util.concurrent.TimeUnit unit) {
                scheduled.add(command);
                return super.scheduleAtFixedRate(command, initialDelay, period, unit);
            }
        };
    }

    static Function<ThreadFactory, ScheduledExecutorService> realExecutors() {
        return Executors::newSingleThreadScheduledExecutor;
    }

    /**
     * A scheduled executor that holds command tasks until {@link #runQueued} runs them, so a test decides the
     * instant the worker starts. The pool underneath still serves the periodic tick and the shutdown.
     */
    static final class QueuedExecutor extends java.util.concurrent.ScheduledThreadPoolExecutor {
        private final List<Runnable> queued = new CopyOnWriteArrayList<>();

        QueuedExecutor() {
            super(1);
        }

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        int queuedCount() {
            return queued.size();
        }

        /** Runs every held command on the caller thread, in the order they were queued. */
        void runQueued() {
            List<Runnable> pending = List.copyOf(queued);
            queued.clear();
            for (Runnable command : pending) {
                command.run();
            }
        }
    }

    /** Seams with the scripted metadata fetcher, the test clock and the given origins. */
    static Seams seams(SourceFetcher fetcher, Function<ThreadFactory, ScheduledExecutorService> executors,
            Optional<ReleaseHosts> hosts) {
        return new Seams(fetcher, PipelineFixtures.CLOCK, DurabilityOps.system(), executors,
                log -> hosts.isPresent() ? hosts : UpdaterOrigins.resolve(null, log),
                PipelineFixtures.ENDPOINTS);
    }

    /** The configured hosts: the three test hosts. */
    static Optional<ReleaseHosts> testHosts() {
        return Optional.of(PipelineFixtures.HOSTS);
    }

    /** The live jar bytes for {@code version}: a valid build and trust file, and a payload. */
    static byte[] liveJar(String version) throws IOException {
        return TransactionFixtures.releaseJar(version);
    }

    /**
     * A jar with the given metadata; a {@code null} entry is left out. Used for the refusal cases.
     */
    static byte[] jarWith(String buildJson, String trustJson) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("payload.bin"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
            if (buildJson != null) {
                zip.putNextEntry(new java.util.zip.ZipEntry(ArtifactVerifier.BUILD_ENTRY));
                zip.write(buildJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (trustJson != null) {
                zip.putNextEntry(new java.util.zip.ZipEntry(ArtifactVerifier.TRUST_ENTRY));
                zip.write(trustJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /**
     * A jar whose build entry appears twice. ZipOutputStream refuses duplicate names, so the second copy is
     * written under a placeholder name of the same length and the name bytes are patched afterwards.
     */
    static byte[] jarWithDuplicateBuild(String buildJson, String trustJson) throws IOException {
        String real = ArtifactVerifier.BUILD_ENTRY;
        String placeholder = real.substring(0, real.length() - 1) + "X";
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String name : List.of(real, placeholder)) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write(buildJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new java.util.zip.ZipEntry(ArtifactVerifier.TRUST_ENTRY));
            zip.write(trustJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] raw = bytes.toByteArray();
        byte[] from = placeholder.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] to = real.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i + from.length <= raw.length; i++) {
            if (java.util.Arrays.equals(raw, i, i + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, raw, i, to.length);
            }
        }
        return raw;
    }

    /** The standard release manifest with its sequence changed to {@code seq}; the signature is not redone. */
    static byte[] alteredManifest(int seq) {
        String text = PipelineFixtures.text(PipelineFixtures.manifest());
        return SignedFixtures.bytes(text.replace("\"seq\":42", "\"seq\":" + seq));
    }

    /** A signature over {@code document} made with a TEST key other than the release key: a bad signature. */
    static byte[] wrongKeySignature(byte[] document) throws Exception {
        return SignedFixtures.envelopeFor(document,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_2, document));
    }

    /** Answers every host's manifest with {@code badDocument}, whose signature does not verify. */
    static void answerBadManifest(PipelineFixtures.ScriptedFetcher fetcher, byte[] badDocument) throws Exception {
        for (ReleaseHost host : PipelineFixtures.HOSTS.hosts()) {
            fetcher.answer(PipelineFixtures.ENDPOINTS.manifest(host), badDocument);
            fetcher.answer(PipelineFixtures.ENDPOINTS.manifestSignature(host), wrongKeySignature(badDocument));
        }
    }

    /** Serves a valid release {@code NEWER} that matches the live jar's trust, for every host. */
    static PipelineFixtures.ScriptedFetcher newerRelease() throws Exception {
        byte[] newJar = TransactionFixtures.releaseJar(NEWER);
        PipelineFixtures.ScriptedFetcher metadata = new PipelineFixtures.ScriptedFetcher();
        TransactionFixtures.serveMetadata(metadata, PipelineFixtures.revocations(),
                TransactionFixtures.manifestFor(newJar, NEWER));
        return metadata;
    }

    static String buildJson(String platform, String version) {
        return "{\"type\":\"quicraft.build\",\"v\":1,\"platform\":\"" + platform + "\",\"version\":\""
                + version + "\"}";
    }

    /** Writes the live jar under {@code dir} and returns its discovery record. */
    static LiveJar writeLive(Path dir, byte[] bytes, String version) throws Exception {
        Files.createDirectories(dir);
        Path live = dir.resolve("quicraft.jar");
        Files.write(live, bytes);
        return new LiveJar(live, dir, bytes.length, TransactionFixtures.sha512Hex(bytes),
                UpdateVersion.parse(version));
    }

    /** A {@link FixedDiscovery} returning {@code live}. */
    static FixedDiscovery discoveryFor(LiveJar live) {
        FixedDiscovery d = new FixedDiscovery();
        d.result = live;
        return d;
    }

    /** The lines of a command output. */
    static final class Output {
        final List<String> lines = new ArrayList<>();

        void add(String line) {
            synchronized (lines) {
                lines.add(line);
            }
        }

        List<String> snapshot() {
            synchronized (lines) {
                return List.copyOf(lines);
            }
        }

        boolean contains(String part) {
            return snapshot().stream().anyMatch(l -> l.contains(part));
        }
    }
}
