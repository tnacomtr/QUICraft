// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * {@code assemble-sig}: builds the detached signature envelope
 * {@code {"type":"quicraft.sig","v":1,"payloadSha256":...,"signatures":[...]}} (docs/updater.md 12.3)
 * from the owner's raw or hex signatures, then immediately verifies the whole pair with the
 * updater's own verifier.
 *
 * <p>It deletes nothing and exits non-zero when verification fails: a written {@code .sig} that does
 * not verify is kept for inspection.
 */
final class AssembleSig {

    static final Set<String> OPTIONS = Set.of("--payload", "--sig", "--out", "--trust", "--now");

    static final Set<String> REPEATABLE = Set.of("--sig");

    private static final int MAX_SIGNATURES = 2;

    private static final int SIGNATURE_BYTES = 64;

    private AssembleSig() {
    }

    static int run(Cli cli, PrintStream out, PrintStream err) throws ToolException {
        Path payloadPath = cli.requirePath("--payload");
        Path outFile = cli.requirePath("--out");
        List<String> specs = cli.list("--sig");
        if (specs.isEmpty() || specs.size() > MAX_SIGNATURES) {
            throw new ToolException("--sig must be given once or twice (1.." + MAX_SIGNATURES
                    + " signatures)");
        }
        Path trustPath = cli.optionalPath("--trust", TrustFiles.defaultPath());
        Instant now = cli.optionalTimestamp("--now", Timestamps.now());

        byte[] payload = ToolFiles.read(payloadPath);
        String payloadName = payloadPath.getFileName() == null
                ? payloadPath.toString() : payloadPath.getFileName().toString();

        StringBuilder json = new StringBuilder(256);
        json.append("{\"type\":\"quicraft.sig\",\"v\":1,\"payloadSha256\":")
                .append(Json.quote(Hexes.sha256Hex(payload)))
                .append(",\"signatures\":[");
        for (int i = 0; i < specs.size(); i++) {
            SignedSignature signed = readSignature(specs.get(i));
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"kid\":").append(Json.quote(signed.kid()))
                    .append(",\"sig\":").append(Json.quote(signed.sig())).append('}');
        }
        json.append("]}");

        ToolFiles.write(outFile, ToolFiles.utf8(json.toString()));
        // Verify the file that is on disk now, with the same code and rules the updater uses.
        int code = Verify.check(trustPath, payload, ToolFiles.read(outFile), payloadName, now, out,
                err);
        if (code != ReleaseTool.EXIT_OK) {
            err.println("release-tool: " + outFile
                    + " was written but does not verify; it was not deleted");
        }
        return code;
    }

    private record SignedSignature(String kid, String sig) {
    }

    /** Parses {@code KID=SIGFILE}; SIGFILE holds the raw 64-byte signature or 128 hex characters. */
    private static SignedSignature readSignature(String spec) throws ToolException {
        int equals = spec.indexOf('=');
        if (equals <= 0 || equals == spec.length() - 1) {
            throw new ToolException("--sig must be KID=SIGFILE: " + spec);
        }
        String kid = spec.substring(0, equals);
        Path sigFile = Path.of(spec.substring(equals + 1));
        if (!Hexes.isLowerHex(kid, 16)) {
            throw new ToolException("--sig needs a 16-character lowercase hex kid: " + spec);
        }
        byte[] sig = decodeSignature(sigFile);
        return new SignedSignature(kid, Hexes.encode(sig));
    }

    /** Accepts the raw 64-byte signature, or 128 hex characters with optional trailing whitespace. */
    private static byte[] decodeSignature(Path file) throws ToolException {
        byte[] bytes = ToolFiles.readCapped(file, Limits.SIGNATURES_MAX_BYTES);
        if (bytes.length == SIGNATURE_BYTES) {
            return bytes;
        }
        String text = new String(bytes, StandardCharsets.US_ASCII).strip();
        if (Hexes.isLowerHex(text, SIGNATURE_BYTES * 2)) {
            return Hexes.decode(text, SIGNATURE_BYTES);
        }
        throw new ToolException(file + " must hold the raw " + SIGNATURE_BYTES
                + "-byte signature or " + SIGNATURE_BYTES * 2 + " lowercase hex characters");
    }
}
