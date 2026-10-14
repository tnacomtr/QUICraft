// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** Outcome of authenticating one document. Only {@link #VERIFIED} carries a document. */
public enum VerificationOutcome {
    /** Structure, signature and required role all passed. */
    VERIFIED,
    /** The document does not match the envelope's {@code payloadSha256} (torn pair). */
    TORN,
    /** No usable trusted key verified, or the required role is missing. */
    BAD_SIGNATURE,
    /** The envelope, the document or the input bytes are structurally invalid. */
    MALFORMED
}
