// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns the path a loader reports for our own jar into a validated {@link LiveJar}
 * (docs/updater.md 12.14, "Both"). The loader adapter supplies only the reported path, the scan
 * directory, the loader's filter, the id reader and the own id. Every refusal is an
 * {@link UpdateRejectedException} with a fixed reason and no path; the underlying IOException, if
 * any, is kept as the cause. A jar we cannot inspect is refused as well.
 */
public final class LiveJarValidator {

    /**
     * Practical size cap for the live jar, far below {@link LiveJar#MAX_SIZE}. A server jar is a
     * few tens of MiB; the cap bounds the one hashing pass.
     */
    static final long MAX_JAR_BYTES = 512L << 20;

    private static final int COPY_BUFFER = 64 * 1024;
    private static final String JAR_SUFFIX = ".jar";
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private LiveJarValidator() {
    }

    /**
     * Validates the live jar.
     *
     * @param reportedSource the path the loader reports for our jar, absolute or relative; it must
     *     not contain {@code .} or {@code ..} name elements
     * @param scanDirectory the loader's scan directory for this kind of jar
     * @param filter the loader's directory filter
     * @param ids reads the id of a jar; a throw refuses the validation
     * @param ownId the id of this project's jar
     * @param onDiskVersion reads the version from a jar's build metadata; a throw refuses the jar
     * @return the validated live jar
     * @throws IOException declared for compatibility; I/O failures are reported as
     *     {@link UpdateRejectedException} with a fixed reason
     * @throws UpdateRejectedException when the jar is not acceptable
     */
    public static LiveJar validate(Path reportedSource, Path scanDirectory, ScanFilter filter,
            JarIdReader ids, String ownId, Function<Path, UpdateVersion> onDiskVersion)
            throws IOException, UpdateRejectedException {
        Objects.requireNonNull(reportedSource, "reportedSource");
        Objects.requireNonNull(scanDirectory, "scanDirectory");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(ownId, "ownId");
        Objects.requireNonNull(onDiskVersion, "onDiskVersion");

        // (a) The spelling is checked before normalisation: "dir/link/../x.jar" must not be
        // validated as "dir/x.jar" when "link" is a symbolic link elsewhere.
        Path absolute = reportedSource.toAbsolutePath();
        requireCanonicalSpelling(absolute);
        Path original = absolute.normalize();
        requireOriginalIsPlainFile(original);

        // (b) Resolve, then check location, name and the loader's filter.
        Path real = realPath(original, "cannot resolve live jar path");
        Path realScan = realPath(scanDirectory, "cannot resolve loader scan directory");
        Path realParent = real.getParent();
        if (realParent == null || !realParent.equals(realScan)) {
            throw refuse("live jar is outside the loader scan directory");
        }
        if (!real.getFileName().toString().endsWith(JAR_SUFFIX)) {
            throw refuse("live jar name does not end with .jar");
        }
        if (!filter.loaderWouldLoad(real)) {
            throw refuse("loader would not load the live jar");
        }

        // (c) Exactly one entry the loader would load carries our id, and it is the live jar.
        requireUniqueOwnJar(real, realScan, filter, ids, ownId);

        // (d) Size cap, one bounded hashing pass, and the on-disk version.
        long size;
        String sha512;
        try (FileChannel ch = FileChannel.open(real,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            size = ch.size();
            if (size <= 0 || size > LiveJar.MAX_SIZE || size > MAX_JAR_BYTES) {
                throw refuse("live jar size is out of range");
            }
            sha512 = hashExactly(ch, size);
        } catch (IOException e) {
            throw new UpdateRejectedException("cannot read live jar", e);
        }

        UpdateVersion version;
        try {
            version = onDiskVersion.apply(real);
        } catch (RuntimeException e) {
            throw new UpdateRejectedException("cannot read the live jar version", e);
        }
        if (version == null) {
            throw refuse("cannot read the live jar version");
        }

        // (e)
        return new LiveJar(real, realScan, size, sha512, version);
    }

    /**
     * Refuses a path with a {@code .} or {@code ..} name element, checked on the path as reported
     * (absolute, not normalised). Symbolic links above the parent are not rejected here.
     */
    private static void requireCanonicalSpelling(Path absolute) throws UpdateRejectedException {
        for (int i = 0; i < absolute.getNameCount(); i++) {
            String element = absolute.getName(i).toString();
            if (".".equals(element) || "..".equals(element)) {
                throw refuse("live jar path is not canonical");
            }
        }
    }

    /** Refuses a symbolic link, a non-regular file, or a parent directory that is a link. */
    private static void requireOriginalIsPlainFile(Path original)
            throws UpdateRejectedException {
        Path parent = original.getParent();
        if (parent == null) {
            throw refuse("live jar path has no parent directory");
        }
        if (Files.isSymbolicLink(parent)) {
            throw refuse("live jar directory is a symbolic link");
        }
        if (!Files.isDirectory(parent, NOFOLLOW)) {
            throw refuse("live jar directory is not a directory");
        }
        if (Files.isSymbolicLink(original)) {
            throw refuse("live jar is a symbolic link");
        }
        if (!Files.isRegularFile(original, NOFOLLOW)) {
            throw refuse("live jar is not a regular file");
        }
    }

    private static Path realPath(Path path, String reason) throws UpdateRejectedException {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UpdateRejectedException(reason, e);
        }
    }

    private static void requireUniqueOwnJar(Path real, Path realScan, ScanFilter filter,
            JarIdReader ids, String ownId) throws UpdateRejectedException {
        int matches = 0;
        Path matchedReal = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(realScan)) {
            for (Path entry : stream) {
                if (!filter.loaderWouldLoad(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                Optional<String> id;
                try {
                    id = ids.idOf(entry);
                } catch (IOException | RuntimeException e) {
                    throw new UpdateRejectedException(
                            "cannot inspect " + name + " for a duplicate QUICraft jar", e);
                }
                if (id == null || id.isEmpty() || !ownId.equals(id.get())) {
                    continue;
                }
                matches++;
                try {
                    matchedReal = entry.toRealPath();
                } catch (IOException e) {
                    throw new UpdateRejectedException(
                            "cannot inspect " + name + " for a duplicate QUICraft jar", e);
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            throw new UpdateRejectedException("cannot list the loader scan directory", e);
        }
        if (matches == 0) {
            throw refuse("live jar does not carry the QUICraft id");
        }
        if (matches > 1) {
            throw refuse("more than one QUICraft jar in the loader scan directory");
        }
        if (!real.equals(matchedReal)) {
            throw refuse("the QUICraft jar in the loader scan directory is not the live jar");
        }
    }

    /** Reads exactly {@code size} bytes and requires end of file after them. */
    private static String hashExactly(FileChannel ch, long size)
            throws IOException, UpdateRejectedException {
        MessageDigest md = sha512();
        ByteBuffer buf = ByteBuffer.allocate(COPY_BUFFER);
        long remaining = size;
        while (remaining > 0) {
            buf.clear();
            if (remaining < buf.capacity()) {
                buf.limit((int) remaining);
            }
            int n = ch.read(buf);
            if (n <= 0) {
                throw refuse("live jar changed while it was read");
            }
            buf.flip();
            md.update(buf);
            remaining -= n;
        }
        buf.clear();
        if (ch.read(buf) != -1) {
            throw refuse("live jar changed while it was read");
        }
        return Hex.encode(md.digest());
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    private static UpdateRejectedException refuse(String reason) {
        return new UpdateRejectedException(reason);
    }
}
