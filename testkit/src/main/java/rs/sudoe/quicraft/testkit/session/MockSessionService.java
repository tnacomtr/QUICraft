// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.session;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.google.gson.JsonObject;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.SessionService;

/**
 * Client side of online-mode login against {@link MockSessionServer}. MCProtocolLib hard-codes
 * Mojang's join endpoint, so this overrides the one call the client makes.
 */
public final class MockSessionService extends SessionService {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final URI joinUri;

    /** @param baseUri e.g. {@code http://mocksession:8080} */
    public MockSessionService(URI baseUri) {
        this.joinUri = baseUri.resolve("/session/minecraft/join");
    }

    @Override
    public void joinServer(GameProfile profile, String authenticationToken, String serverId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", authenticationToken);
        body.addProperty("selectedProfile", profile.getIdAsString().replace("-", ""));
        body.addProperty("name", profile.getName());
        body.addProperty("serverId", serverId);
        HttpRequest request = HttpRequest.newBuilder(joinUri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 204) {
                throw new IOException("mock session join returned HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
