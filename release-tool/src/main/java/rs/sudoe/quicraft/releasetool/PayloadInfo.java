// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;
import rs.sudoe.quicraft.updater.UpdateDocuments;

/**
 * {@code payload-info}: prints the size, the SHA-256 and, for every key in the trust file, the exact
 * OpenSSL command the owner runs to sign the payload bytes by hand (docs/updater.md 12.3, "Signed
 * bytes = the exact file bytes").
 *
 * <p>The command is printed for the payload's own file name, so the owner runs it in the directory
 * that holds the document. Nothing here signs anything: the tool has no private key.
 */
final class PayloadInfo {

    static final Set<String> OPTIONS = Set.of("--payload", "--trust", "--out");

    private PayloadInfo() {
    }

    static int run(Cli cli, PrintStream out) throws ToolException {
        Path payload = cli.requirePath("--payload");
        Path trustPath = cli.optionalPath("--trust", TrustFiles.defaultPath());
        byte[] bytes = ToolFiles.read(payload);
        UpdateDocuments.Trust trust = TrustFiles.load(trustPath);

        // --out gives the name used in the printed commands; it defaults to the payload's own name,
        // which is what the owner will upload.
        Path signedName = cli.optionalPath("--out", payload.getFileName());
        if (signedName == null || signedName.getFileName() == null) {
            throw new ToolException("--out must name the signed file: " + payload);
        }
        String name = signedName.getFileName().toString();

        out.println("payload: " + payload);
        out.println("size: " + bytes.length);
        out.println("sha256: " + Hexes.sha256Hex(bytes));
        for (UpdateDocuments.TrustedKey key : trust.keys()) {
            out.println("openssl pkeyutl -sign -inkey <private-key.pem> -rawin -in " + name
                    + " -out " + name + "." + key.kid() + ".sigbin");
        }
        return ReleaseTool.EXIT_OK;
    }
}
