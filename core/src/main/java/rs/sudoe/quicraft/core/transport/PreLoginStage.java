// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

/** /3 pre-login admission lifecycle callbacks (docs/protocol.md §12.13). */
public interface PreLoginStage {
    void onLoginSuccess();
    void onActivation();
    void pauseClientWait();
    void resumeClientWait();
    void onShed();
}
