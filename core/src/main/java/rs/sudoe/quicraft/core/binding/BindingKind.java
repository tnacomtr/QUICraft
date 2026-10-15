// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

/**
 * The five binding message kinds of docs/protocol.md §12.8. OFFER, SERVER_PROOF and SERVER_SWITCH
 * travel server to client; CLIENT_PROOF and CLIENT_SWITCH travel client to server.
 */
public enum BindingKind {
    OFFER(0),
    CLIENT_PROOF(1),
    SERVER_PROOF(2),
    CLIENT_SWITCH(3),
    SERVER_SWITCH(4);

    /** Value of the {@code kind} byte. */
    private final int code;

    BindingKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** True for the three kinds the server sends. */
    public boolean fromServer() {
        return this == OFFER || this == SERVER_PROOF || this == SERVER_SWITCH;
    }

    /** Returns the kind for {@code code}, or {@code null} when unknown. */
    public static BindingKind fromCode(int code) {
        for (BindingKind kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        return null;
    }
}