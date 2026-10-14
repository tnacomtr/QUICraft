// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

/**
 * A user-facing failure of the release tool. The message is printed verbatim to stderr and the
 * process exits non-zero; it never carries document contents or key material.
 */
final class ToolException extends Exception {

    ToolException(String message) {
        super(message);
    }

    ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
