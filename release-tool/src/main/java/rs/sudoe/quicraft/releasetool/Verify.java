// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import rs.sudoe.quicraft.updater.DocumentParser;
import rs.sudoe.quicraft.updater.EnvelopeStatus;
import rs.sudoe.quicraft.updater.EnvelopeVerification;
import rs.sudoe.quicraft.updater.FreshnessPolicy;
import rs.sudoe.quicraft.updater.KeyRole;
import rs.sudoe.quicraft.updater.SignatureVerifier;
import rs.sudoe.quicraft.updater.TrustSnapshot;
import rs.sudoe.quicraft.updater.UpdateDocuments;
import rs.sudoe.quicraft.updater.UpdateRejectedException;
import rs.sudoe.quicraft.updater.VerifiedSigner;

/**
 * {@code verify}: checks one document and its detached {@code .sig} against a trust file, using the
 * updater's own parser, {@link SignatureVerifier} and {@link TrustSnapshot} (docs/updater.md 12.3,
 * 12.12). No other crypto library is involved.
 *
 * <p>It checks, in order: that the payload is a valid release manifest or revocations document; that
 * the envelope parses and its {@code payloadSha256} matches the exact payload bytes; that at least
 * one signature verifies under a trusted, non-revoked key holding the role the document type
 * requires ({@code release} for a manifest, {@code revocation} for revocations); that the document's
 * lifetime obeys the updater's own rule for its type, {@code issued < expires} and at most 45 days
 * for a manifest or 400 days for revocations ({@link FreshnessPolicy#structuralTimesValid}); and
 * that the document has not expired at {@code --now} ({@link FreshnessPolicy#unexpired}). It exits
 * non-zero on any failure and prints which kid verified.
 */
final class Verify {

    static final Set<String> OPTIONS = Set.of("--trust", "--payload", "--sig", "--now");

    private Verify() {
    }

    static int run(Cli cli, PrintStream out, PrintStream err) throws ToolException {
        Path trustPath = cli.requirePath("--trust");
        Path payloadPath = cli.requirePath("--payload");
        Path sigPath = cli.requirePath("--sig");
        Instant now = cli.optionalTimestamp("--now", Timestamps.now());
        String payloadName = payloadPath.getFileName() == null
                ? payloadPath.toString() : payloadPath.getFileName().toString();
        return check(trustPath, ToolFiles.read(payloadPath), ToolFiles.read(sigPath), payloadName,
                now, out, err);
    }

    /**
     * Verifies one pair.
     *
     * @return {@link ReleaseTool#EXIT_OK} when the pair verifies, non-zero otherwise
     */
    static int check(Path trustPath, byte[] payloadBytes, byte[] sigBytes, String payloadName,
            Instant now, PrintStream out, PrintStream err) {
        Payload payload;
        UpdateDocuments.SignatureEnvelope envelope;
        TrustSnapshot snapshot;
        try {
            UpdateDocuments.Trust trust = TrustFiles.load(trustPath);
            payload = parsePayload(payloadBytes);
            envelope = DocumentParser.parseSignatures(sigBytes);
            snapshot = TrustSnapshot.of(trust, Set.of());
        } catch (ToolException | UpdateRejectedException e) {
            err.println("release-tool: " + e.getMessage());
            return ReleaseTool.EXIT_FAILED;
        }

        EnvelopeVerification verification =
                SignatureVerifier.check(payloadBytes, envelope, snapshot);
        if (verification.status() == EnvelopeStatus.TORN) {
            err.println("release-tool: payloadSha256 " + envelope.payloadSha256()
                    + " does not match the payload bytes (torn document/envelope pair)");
            return ReleaseTool.EXIT_FAILED;
        }
        if (verification.status() != EnvelopeStatus.VERIFIED) {
            err.println("release-tool: no signature verified under a trusted, non-revoked key");
            return ReleaseTool.EXIT_FAILED;
        }

        KeyRole required = payload.requiredRole();
        boolean roleOk = required == KeyRole.RELEASE
                ? verification.signers().hasRelease() : verification.signers().hasRevocation();
        if (!roleOk) {
            err.println("release-tool: the signature verifies, but no verified signer holds the "
                    + required.wireName() + " role required for a " + payload.description());
            return ReleaseTool.EXIT_FAILED;
        }

        if (!FreshnessPolicy.structuralTimesValid(payload.issued(), payload.expires(),
                payload.maxLifetime())) {
            err.println("release-tool: the " + payload.description() + " has an invalid lifetime:"
                    + " issued " + Timestamps.formatUnchecked(payload.issued()) + ", expires "
                    + Timestamps.formatUnchecked(payload.expires()) + " (the updater requires"
                    + " issued < expires and at most " + payload.maxLifetime().toDays() + " days)");
            return ReleaseTool.EXIT_FAILED;
        }

        if (!FreshnessPolicy.unexpired(payload.expires(), now)) {
            err.println("release-tool: the " + payload.description() + " expired at "
                    + Timestamps.formatUnchecked(payload.expires()) + " (now "
                    + Timestamps.formatUnchecked(now) + ")");
            return ReleaseTool.EXIT_FAILED;
        }

        for (VerifiedSigner signer : verification.signers().signers()) {
            out.println("verified by kid " + signer.kid() + " (roles " + roles(signer) + ")");
        }
        out.println("verified " + payloadName + ": " + payload.description() + " seq "
                + payload.seq() + ", expires " + Timestamps.formatUnchecked(payload.expires()));
        return ReleaseTool.EXIT_OK;
    }

    private static String roles(VerifiedSigner signer) {
        List<String> names = new ArrayList<>();
        for (KeyRole role : signer.roles()) {
            names.add(role.wireName());
        }
        return String.join(",", new TreeSet<>(names));
    }

    /**
     * The parsed payload with the policy facts the type decides. {@code issued} is kept because the
     * lifetime rules ({@link FreshnessPolicy#structuralTimesValid}) need it, and the
     * {@link DocumentParser} deliberately does not enforce them.
     */
    private record Payload(
            String description, KeyRole requiredRole, long seq, Instant issued, Instant expires,
            Duration maxLifetime) {
    }

    /**
     * Parses the payload by its own {@code type}: a release manifest, or a revocations document.
     * Both are tried because the {@code .sig} carries no trusted type.
     */
    private static Payload parsePayload(byte[] bytes) throws UpdateRejectedException {
        try {
            UpdateDocuments.ReleaseManifest release = DocumentParser.parseRelease(bytes);
            return new Payload("release manifest", KeyRole.RELEASE, release.seq(), release.issued(),
                    release.expires(), FreshnessPolicy.MANIFEST_MAX_LIFETIME);
        } catch (UpdateRejectedException notRelease) {
            UpdateDocuments.Revocations revocations = DocumentParser.parseRevocations(bytes);
            return new Payload("revocations document", KeyRole.REVOCATION, revocations.seq(),
                    revocations.issued(), revocations.expires(),
                    FreshnessPolicy.REVOCATIONS_MAX_LIFETIME);
        }
    }
}
