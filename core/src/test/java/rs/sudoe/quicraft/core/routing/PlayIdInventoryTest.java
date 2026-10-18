// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The checked-in live-game PLAY packet inventory for protocol 775
 * ({@code rs/sudoe/quicraft/core/routing/play-ids-775.txt}). The Fabric client gametest
 * {@code RoutingTableGameTest} captures it from the game's own protocol templates
 * ({@code -Pquicraft.emitPlayIds}, see docs/platforms/fabric.md) and compares the live protocol
 * against it, so a refresh is mechanical and never hand-written. Each data line is
 * {@code <direction> <id> <name>}; lines starting with {@code #} are provenance comments.
 *
 * <p>{@link #CAPTURED} deliberately duplicates the fixture. The fixture is data that the gametest
 * refreshes, so without an independent copy a swapped, missing or extra line would pass unnoticed
 * here. Update both together from the gametest's fresh output.
 */
class PlayIdInventoryTest {
    private static final int PROTOCOL = 775;
    private static final String RESOURCE = RoutingTableLoader.RESOURCE_DIR + "/play-ids-775.txt";

    /**
     * The complete protocol-775 inventory exactly as captured from the live game, in the fixture's
     * own {@code <direction> <id> <name>} format. This is the pin: every id in both directions and
     * the name bound to it.
     */
    private static final String CAPTURED =
            "clientbound 0 minecraft:bundle_delimiter\n"
            + "clientbound 1 minecraft:add_entity\n"
            + "clientbound 2 minecraft:animate\n"
            + "clientbound 3 minecraft:award_stats\n"
            + "clientbound 4 minecraft:block_changed_ack\n"
            + "clientbound 5 minecraft:block_destruction\n"
            + "clientbound 6 minecraft:block_entity_data\n"
            + "clientbound 7 minecraft:block_event\n"
            + "clientbound 8 minecraft:block_update\n"
            + "clientbound 9 minecraft:boss_event\n"
            + "clientbound 10 minecraft:change_difficulty\n"
            + "clientbound 11 minecraft:chunk_batch_finished\n"
            + "clientbound 12 minecraft:chunk_batch_start\n"
            + "clientbound 13 minecraft:chunks_biomes\n"
            + "clientbound 14 minecraft:clear_titles\n"
            + "clientbound 15 minecraft:command_suggestions\n"
            + "clientbound 16 minecraft:commands\n"
            + "clientbound 17 minecraft:container_close\n"
            + "clientbound 18 minecraft:container_set_content\n"
            + "clientbound 19 minecraft:container_set_data\n"
            + "clientbound 20 minecraft:container_set_slot\n"
            + "clientbound 21 minecraft:cookie_request\n"
            + "clientbound 22 minecraft:cooldown\n"
            + "clientbound 23 minecraft:custom_chat_completions\n"
            + "clientbound 24 minecraft:custom_payload\n"
            + "clientbound 25 minecraft:damage_event\n"
            + "clientbound 26 minecraft:debug/block_value\n"
            + "clientbound 27 minecraft:debug/chunk_value\n"
            + "clientbound 28 minecraft:debug/entity_value\n"
            + "clientbound 29 minecraft:debug/event\n"
            + "clientbound 30 minecraft:debug_sample\n"
            + "clientbound 31 minecraft:delete_chat\n"
            + "clientbound 32 minecraft:disconnect\n"
            + "clientbound 33 minecraft:disguised_chat\n"
            + "clientbound 34 minecraft:entity_event\n"
            + "clientbound 35 minecraft:entity_position_sync\n"
            + "clientbound 36 minecraft:explode\n"
            + "clientbound 37 minecraft:forget_level_chunk\n"
            + "clientbound 38 minecraft:game_event\n"
            + "clientbound 39 minecraft:game_rule_values\n"
            + "clientbound 40 minecraft:game_test_highlight_pos\n"
            + "clientbound 41 minecraft:mount_screen_open\n"
            + "clientbound 42 minecraft:hurt_animation\n"
            + "clientbound 43 minecraft:initialize_border\n"
            + "clientbound 44 minecraft:keep_alive\n"
            + "clientbound 45 minecraft:level_chunk_with_light\n"
            + "clientbound 46 minecraft:level_event\n"
            + "clientbound 47 minecraft:level_particles\n"
            + "clientbound 48 minecraft:light_update\n"
            + "clientbound 49 minecraft:login\n"
            + "clientbound 50 minecraft:low_disk_space_warning\n"
            + "clientbound 51 minecraft:map_item_data\n"
            + "clientbound 52 minecraft:merchant_offers\n"
            + "clientbound 53 minecraft:move_entity_pos\n"
            + "clientbound 54 minecraft:move_entity_pos_rot\n"
            + "clientbound 55 minecraft:move_minecart_along_track\n"
            + "clientbound 56 minecraft:move_entity_rot\n"
            + "clientbound 57 minecraft:move_vehicle\n"
            + "clientbound 58 minecraft:open_book\n"
            + "clientbound 59 minecraft:open_screen\n"
            + "clientbound 60 minecraft:open_sign_editor\n"
            + "clientbound 61 minecraft:ping\n"
            + "clientbound 62 minecraft:pong_response\n"
            + "clientbound 63 minecraft:place_ghost_recipe\n"
            + "clientbound 64 minecraft:player_abilities\n"
            + "clientbound 65 minecraft:player_chat\n"
            + "clientbound 66 minecraft:player_combat_end\n"
            + "clientbound 67 minecraft:player_combat_enter\n"
            + "clientbound 68 minecraft:player_combat_kill\n"
            + "clientbound 69 minecraft:player_info_remove\n"
            + "clientbound 70 minecraft:player_info_update\n"
            + "clientbound 71 minecraft:player_look_at\n"
            + "clientbound 72 minecraft:player_position\n"
            + "clientbound 73 minecraft:player_rotation\n"
            + "clientbound 74 minecraft:recipe_book_add\n"
            + "clientbound 75 minecraft:recipe_book_remove\n"
            + "clientbound 76 minecraft:recipe_book_settings\n"
            + "clientbound 77 minecraft:remove_entities\n"
            + "clientbound 78 minecraft:remove_mob_effect\n"
            + "clientbound 79 minecraft:reset_score\n"
            + "clientbound 80 minecraft:resource_pack_pop\n"
            + "clientbound 81 minecraft:resource_pack_push\n"
            + "clientbound 82 minecraft:respawn\n"
            + "clientbound 83 minecraft:rotate_head\n"
            + "clientbound 84 minecraft:section_blocks_update\n"
            + "clientbound 85 minecraft:select_advancements_tab\n"
            + "clientbound 86 minecraft:server_data\n"
            + "clientbound 87 minecraft:set_action_bar_text\n"
            + "clientbound 88 minecraft:set_border_center\n"
            + "clientbound 89 minecraft:set_border_lerp_size\n"
            + "clientbound 90 minecraft:set_border_size\n"
            + "clientbound 91 minecraft:set_border_warning_delay\n"
            + "clientbound 92 minecraft:set_border_warning_distance\n"
            + "clientbound 93 minecraft:set_camera\n"
            + "clientbound 94 minecraft:set_chunk_cache_center\n"
            + "clientbound 95 minecraft:set_chunk_cache_radius\n"
            + "clientbound 96 minecraft:set_cursor_item\n"
            + "clientbound 97 minecraft:set_default_spawn_position\n"
            + "clientbound 98 minecraft:set_display_objective\n"
            + "clientbound 99 minecraft:set_entity_data\n"
            + "clientbound 100 minecraft:set_entity_link\n"
            + "clientbound 101 minecraft:set_entity_motion\n"
            + "clientbound 102 minecraft:set_equipment\n"
            + "clientbound 103 minecraft:set_experience\n"
            + "clientbound 104 minecraft:set_health\n"
            + "clientbound 105 minecraft:set_held_slot\n"
            + "clientbound 106 minecraft:set_objective\n"
            + "clientbound 107 minecraft:set_passengers\n"
            + "clientbound 108 minecraft:set_player_inventory\n"
            + "clientbound 109 minecraft:set_player_team\n"
            + "clientbound 110 minecraft:set_score\n"
            + "clientbound 111 minecraft:set_simulation_distance\n"
            + "clientbound 112 minecraft:set_subtitle_text\n"
            + "clientbound 113 minecraft:set_time\n"
            + "clientbound 114 minecraft:set_title_text\n"
            + "clientbound 115 minecraft:set_titles_animation\n"
            + "clientbound 116 minecraft:sound_entity\n"
            + "clientbound 117 minecraft:sound\n"
            + "clientbound 118 minecraft:start_configuration\n"
            + "clientbound 119 minecraft:stop_sound\n"
            + "clientbound 120 minecraft:store_cookie\n"
            + "clientbound 121 minecraft:system_chat\n"
            + "clientbound 122 minecraft:tab_list\n"
            + "clientbound 123 minecraft:tag_query\n"
            + "clientbound 124 minecraft:take_item_entity\n"
            + "clientbound 125 minecraft:teleport_entity\n"
            + "clientbound 126 minecraft:test_instance_block_status\n"
            + "clientbound 127 minecraft:ticking_state\n"
            + "clientbound 128 minecraft:ticking_step\n"
            + "clientbound 129 minecraft:transfer\n"
            + "clientbound 130 minecraft:update_advancements\n"
            + "clientbound 131 minecraft:update_attributes\n"
            + "clientbound 132 minecraft:update_mob_effect\n"
            + "clientbound 133 minecraft:update_recipes\n"
            + "clientbound 134 minecraft:update_tags\n"
            + "clientbound 135 minecraft:projectile_power\n"
            + "clientbound 136 minecraft:custom_report_details\n"
            + "clientbound 137 minecraft:server_links\n"
            + "clientbound 138 minecraft:waypoint\n"
            + "clientbound 139 minecraft:clear_dialog\n"
            + "clientbound 140 minecraft:show_dialog\n"
            + "serverbound 0 minecraft:accept_teleportation\n"
            + "serverbound 1 minecraft:attack\n"
            + "serverbound 2 minecraft:block_entity_tag_query\n"
            + "serverbound 3 minecraft:bundle_item_selected\n"
            + "serverbound 4 minecraft:change_difficulty\n"
            + "serverbound 5 minecraft:change_game_mode\n"
            + "serverbound 6 minecraft:chat_ack\n"
            + "serverbound 7 minecraft:chat_command\n"
            + "serverbound 8 minecraft:chat_command_signed\n"
            + "serverbound 9 minecraft:chat\n"
            + "serverbound 10 minecraft:chat_session_update\n"
            + "serverbound 11 minecraft:chunk_batch_received\n"
            + "serverbound 12 minecraft:client_command\n"
            + "serverbound 13 minecraft:client_tick_end\n"
            + "serverbound 14 minecraft:client_information\n"
            + "serverbound 15 minecraft:command_suggestion\n"
            + "serverbound 16 minecraft:configuration_acknowledged\n"
            + "serverbound 17 minecraft:container_button_click\n"
            + "serverbound 18 minecraft:container_click\n"
            + "serverbound 19 minecraft:container_close\n"
            + "serverbound 20 minecraft:container_slot_state_changed\n"
            + "serverbound 21 minecraft:cookie_response\n"
            + "serverbound 22 minecraft:custom_payload\n"
            + "serverbound 23 minecraft:debug_subscription_request\n"
            + "serverbound 24 minecraft:edit_book\n"
            + "serverbound 25 minecraft:entity_tag_query\n"
            + "serverbound 26 minecraft:interact\n"
            + "serverbound 27 minecraft:jigsaw_generate\n"
            + "serverbound 28 minecraft:keep_alive\n"
            + "serverbound 29 minecraft:lock_difficulty\n"
            + "serverbound 30 minecraft:move_player_pos\n"
            + "serverbound 31 minecraft:move_player_pos_rot\n"
            + "serverbound 32 minecraft:move_player_rot\n"
            + "serverbound 33 minecraft:move_player_status_only\n"
            + "serverbound 34 minecraft:move_vehicle\n"
            + "serverbound 35 minecraft:paddle_boat\n"
            + "serverbound 36 minecraft:pick_item_from_block\n"
            + "serverbound 37 minecraft:pick_item_from_entity\n"
            + "serverbound 38 minecraft:ping_request\n"
            + "serverbound 39 minecraft:place_recipe\n"
            + "serverbound 40 minecraft:player_abilities\n"
            + "serverbound 41 minecraft:player_action\n"
            + "serverbound 42 minecraft:player_command\n"
            + "serverbound 43 minecraft:player_input\n"
            + "serverbound 44 minecraft:player_loaded\n"
            + "serverbound 45 minecraft:pong\n"
            + "serverbound 46 minecraft:recipe_book_change_settings\n"
            + "serverbound 47 minecraft:recipe_book_seen_recipe\n"
            + "serverbound 48 minecraft:rename_item\n"
            + "serverbound 49 minecraft:resource_pack\n"
            + "serverbound 50 minecraft:seen_advancements\n"
            + "serverbound 51 minecraft:select_trade\n"
            + "serverbound 52 minecraft:set_beacon\n"
            + "serverbound 53 minecraft:set_carried_item\n"
            + "serverbound 54 minecraft:set_command_block\n"
            + "serverbound 55 minecraft:set_command_minecart\n"
            + "serverbound 56 minecraft:set_creative_mode_slot\n"
            + "serverbound 57 minecraft:set_game_rule\n"
            + "serverbound 58 minecraft:set_jigsaw_block\n"
            + "serverbound 59 minecraft:set_structure_block\n"
            + "serverbound 60 minecraft:set_test_block\n"
            + "serverbound 61 minecraft:sign_update\n"
            + "serverbound 62 minecraft:spectate_entity\n"
            + "serverbound 63 minecraft:swing\n"
            + "serverbound 64 minecraft:teleport_to_entity\n"
            + "serverbound 65 minecraft:test_instance_block_action\n"
            + "serverbound 66 minecraft:use_item_on\n"
            + "serverbound 67 minecraft:use_item\n"
            + "serverbound 68 minecraft:custom_click_action";

    @Test
    void theFixtureIsExactlyTheInventoryTheLiveGameCaptured() throws Exception {
        Map<Direction, Map<Integer, String>> expected = parse("CAPTURED", CAPTURED);
        Map<Direction, Map<Integer, String>> actual = parse(RESOURCE, fixture());
        for (Direction direction : Direction.values()) {
            assertEquals(expected.get(direction), actual.get(direction), "the "
                    + direction.wireName() + " inventory changed: "
                    + differences(expected.get(direction), actual.get(direction)));
        }
    }

    @Test
    void bothDirectionsArePresentAndNonEmpty() throws Exception {
        Map<Direction, Map<Integer, String>> inventory = parse(RESOURCE, fixture());
        for (Direction direction : Direction.values()) {
            assertFalse(inventory.get(direction).isEmpty(),
                    "no " + direction.wireName() + " packets in " + RESOURCE);
        }
    }

    /** What differs, compactly: missing ids, unexpected ids and ids bound to another name. */
    private static String differences(Map<Integer, String> expected, Map<Integer, String> actual) {
        StringBuilder message = new StringBuilder();
        int shown = 0;
        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            String liveName = actual.get(entry.getKey());
            if (liveName == null) {
                shown = append(message, shown, "missing " + entry.getKey() + "=" + entry.getValue());
            } else if (!liveName.equals(entry.getValue())) {
                shown = append(message, shown, "id " + entry.getKey() + " is " + liveName
                        + ", not " + entry.getValue());
            }
        }
        for (Map.Entry<Integer, String> entry : actual.entrySet()) {
            if (!expected.containsKey(entry.getKey())) {
                shown = append(message, shown, "unexpected " + entry.getKey() + "=" + entry.getValue());
            }
        }
        return shown == 0 ? "the same entries" : message.toString();
    }

    private static int append(StringBuilder message, int shown, String difference) {
        if (shown == 10) {
            message.append(", ...");
        } else if (shown < 10) {
            message.append(shown == 0 ? "" : ", ").append(difference);
        }
        return shown + 1;
    }

    private static Map<Direction, Map<Integer, String>> parse(String source, String text) {
        Map<Direction, Map<Integer, String>> byDirection =
                new EnumMap<Direction, Map<Integer, String>>(Direction.class);
        for (Direction direction : Direction.values()) {
            byDirection.put(direction, new TreeMap<Integer, String>());
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            String where = source + " line " + (i + 1);
            String[] parts = line.split("\\s+");
            assertEquals(3, parts.length, where + " is not <direction> <id> <name>: " + line);
            Direction direction;
            try {
                direction = Direction.of(parts[0]);
            } catch (IllegalArgumentException e) {
                throw new AssertionError(where + " has an unknown direction: " + line, e);
            }
            int id;
            try {
                id = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new AssertionError(where + " has no integer id: " + line, e);
            }
            assertTrue(id >= 0, where + " has a negative id: " + line);
            assertTrue(parts[2].startsWith("minecraft:"),
                    where + " does not name a namespaced packet: " + line);
            String previous = byDirection.get(direction).put(id, parts[2]);
            assertTrue(previous == null, where + " repeats " + direction.wireName() + " id " + id
                    + " (" + previous + " and " + parts[2] + ")");
        }
        return byDirection;
    }

    private static String fixture() throws IOException {
        InputStream in = PlayIdInventoryTest.class.getClassLoader().getResourceAsStream(RESOURCE);
        assertNotNull(in, "missing " + RESOURCE);
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
