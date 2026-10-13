// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** Result of checking one detached signature envelope against one document. */
public enum EnvelopeStatus {
    /** The payload hash matches and at least one usable trusted key verified. */
    VERIFIED,
    /** The payload hash matches but no entry verified under a usable trusted key. */
    NO_VALID_SIGNER,
    /** The document bytes do not match {@code payloadSha256}: a torn document/envelope pair. */
    TORN
}
