// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import rs.sudoe.quicraft.fabric.Hooks;

/**
 * What the client mixins call for a {@code /3} join (docs/protocol.md 12.4 to 12.13). Every method
 * does nothing for a connection that is not {@code /3} (TCP, {@code /2}, singleplayer), so the
 * hooks cost one map lookup there, and every method fails safe: a throw from project code ends the
 * {@code /3} attempt, which retries over TCP, and never reaches the game's own handler.
 */
public final class V3Hooks {
    private V3Hooks() {}

    /** Hello arrived. False cancels vanilla's handling: the {@code /3} attempt is over. */
    public static boolean hello(Connection connection, boolean shouldAuthenticate) {
        V3Client client = Transports.v3(connection);
        if (client == null) {
            return true;
        }
        try {
            Hooks.enter("v3 hello");
            return client.onHello(shouldAuthenticate);
        } catch (Throwable t) {
            return failed("v3 hello", client, t);
        }
    }

    /** Vanilla generated the shared secret S. */
    public static void secret(Connection connection, SecretKey key) {
        V3Client client = Transports.v3(connection);
        if (client == null) {
            return;
        }
        try {
            Hooks.enter("v3 secret");
            client.onSecret(key.getEncoded());
        } catch (Throwable t) {
            failed("v3 secret", client, t);
        }
    }

    /** The session-server join finished; {@code error} is null when it succeeded. */
    public static void authenticated(Connection connection, Component error) {
        V3Client client = Transports.v3(connection);
        if (client == null) {
            return;
        }
        try {
            Hooks.enter("v3 authenticated");
            client.onAuthenticated(error == null);
        } catch (Throwable t) {
            failed("v3 authenticated", client, t);
        }
    }

    /** Vanilla installed its cipher handlers. */
    public static void encryptionStarted(Connection connection, Cipher decrypt) {
        V3Client client = Transports.v3(connection);
        if (client == null) {
            return;
        }
        try {
            Hooks.enter("v3 encryption");
            client.onEncryptionStarted(decrypt);
        } catch (Throwable t) {
            failed("v3 encryption", client, t);
        }
    }

    /** Login Finished arrived. False cancels vanilla's handling: the {@code /3} attempt is over. */
    public static boolean loginFinished(Connection connection) {
        V3Client client = Transports.v3(connection);
        if (client == null) {
            return true;
        }
        try {
            Hooks.enter("v3 login finished");
            return client.onLoginFinished();
        } catch (Throwable t) {
            return failed("v3 login finished", client, t);
        }
    }

    /** Vanilla switched the connection to configuration. */
    public static void configurationStarted(Connection connection) {
        V3Client client = Transports.v3(connection);
        if (client == null) {
            return;
        }
        try {
            Hooks.enter("v3 configuration");
            client.onConfigurationStarted();
        } catch (Throwable t) {
            failed("v3 configuration", client, t);
        }
    }

    /**
     * The details the disconnect screen shows: under {@code quic-only} a failed {@code /3} attempt
     * says so instead of the generic "end of stream".
     */
    public static DisconnectionDetails disconnectDetails(Connection connection, DisconnectionDetails details) {
        try {
            // Not through the controller: by now nothing but this map need still hold it.
            String text = Transports.v3Failure(connection);
            if (text != null) {
                return new DisconnectionDetails(Component.literal(text));
            }
        } catch (Throwable t) {
            Hooks.failed("v3 disconnect details", t);
        }
        return details;
    }

    private static boolean failed(String hook, V3Client client, Throwable t) {
        Hooks.failed(hook, t);
        try {
            client.fail(t);
        } catch (Throwable ignored) {
            // The connection is closing; nothing else to do.
        }
        return false;
    }
}
