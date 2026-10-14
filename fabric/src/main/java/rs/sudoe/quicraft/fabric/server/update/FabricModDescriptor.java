// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The two parts of a {@code fabric.mod.json} the duplicate scan needs (docs/updater.md 12.14): the mod
 * {@code id}, and the ids the mod {@code provides}. A jar that only declares {@code quicraft} in
 * {@code provides} is a second QUICraft entry the loader would load, so the scan has to see it too.
 *
 * <p>A descriptor that exists but cannot be read is never "not a mod": it throws, following
 * {@code JarIdReader}'s contract, so the validator refuses the live jar with its "cannot inspect" reason
 * rather than counting a jar it could not read as no duplicate. Only a descriptor that is a JSON object with
 * nothing the scan recognizes answers "not a mod".
 *
 * <p>Plain logic with no game classes, so it is easy to unit test later.
 */
final class FabricModDescriptor {
    private static final String ID_FIELD = "id";
    private static final String PROVIDES_FIELD = "provides";

    private FabricModDescriptor() {
    }

    /**
     * Reads the id a jar answers to for the duplicate scan: its own {@code id}, or {@code ownId} when the
     * descriptor declares that id in {@code provides}.
     *
     * @param descriptor the raw {@code fabric.mod.json} bytes
     * @param ownId this project's mod id
     * @return the id, or empty when the descriptor is a valid JSON object with no string {@code id} and no
     *     {@code ownId} in {@code provides}, i.e. not a mod the scan has to care about
     * @throws IOException when the descriptor exists but cannot be parsed: it is not JSON, or its root is not
     *     an object. {@code JarIdReader} requires an adapter to throw rather than return empty for a sibling
     *     descriptor it cannot parse, so the validator refuses with "cannot inspect"
     */
    static Optional<String> idOf(byte[] descriptor, String ownId) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseString(new String(descriptor, StandardCharsets.UTF_8));
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("fabric.mod.json cannot be parsed", e);
        }
        if (!root.isJsonObject()) {
            throw new IOException("fabric.mod.json is not a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        Optional<String> id = stringOf(object.get(ID_FIELD));
        if (id.isPresent() && ownId.equals(id.get())) {
            return id;
        }
        if (provides(object.get(PROVIDES_FIELD), ownId)) {
            return Optional.of(ownId);
        }
        return id;
    }

    private static Optional<String> stringOf(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return Optional.empty();
        }
        return Optional.of(element.getAsString());
    }

    /** @return true when the {@code provides} array names {@code id} */
    private static boolean provides(JsonElement element, String id) {
        if (element == null || !element.isJsonArray()) {
            return false;
        }
        for (JsonElement provided : element.getAsJsonArray()) {
            if (stringOf(provided).filter(id::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
