/*
 * Copyright (C) 2026 YLFXM
 *
 * MineColonies: Crossroads - a feature expansion addon for MineColonies.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.YLFXM.rp.crossroads.road;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The translation key for the reason behind a failed planning or building action.
 *
 * <p>Failure messages are English and stay English: they are the developer-facing
 * text in logs, and several places branch on it ({@code contains("budget")}). What a
 * player must never see is that English, so every player-visible reason is looked up
 * here and rendered through {@link RoadNotices#reason}. Anything not in the table
 * degrades to {@code rp_crossroads.reason.internal}, which points at the log
 * instead of leaking the raw sentence.</p>
 *
 * <p>Two tables: exact matches, and prefixes for messages that append a value
 * (a road label, a position, a length). The value is dropped from the player text
 * on purpose -- a localised sentence plus an untranslated fragment reads worse than
 * the sentence alone, and the exact text is in the log.</p>
 */
public final class RoadReasons {
    /** Unknown failure: the detail belongs in the log, not in the chat line. */
    public static final String INTERNAL = "rp_crossroads.reason.internal";

    private static final Map<String, String> EXACT = new LinkedHashMap<>();
    private static final Map<String, String> PREFIX = new LinkedHashMap<>();

    static {
        // Blueprint generation.
        exact("The generated blueprint was empty.", "empty_blueprint");
        exact("No bundled road unit matches this plan's style, variant and width.", "no_unit");
        exact("Select road endpoints first.", "no_endpoints");
        exact("Road foundation, stairs or lamps exceed this world's build height.", "height_limit");
        exact("Generated road blueprint exceeds the permitted volume.", "volume_limit");

        // Joint (crossing) work.
        exact("Open blueprint editing on the existing road before changing its junction height.",
                "junction_height_editing");
        exact("The preview road is unavailable.", "preview_unavailable");
        exact("The preview road is busy or unavailable.", "preview_busy");
        exact("The selected roads no longer overlap.", "no_overlap");
        exact("Load the entire joint work area before construction.", "chunks_not_loaded");
        exact("The two selected roads have no common reachable junction height.", "no_common_height");
        exact("An existing junction fixes a different height.", "existing_junction_height");
        exact("The approach is constrained by another junction height.", "approach_constrained");
        exact("The joint native work volume exceeds the permitted size.", "joint_volume_limit");

        // Crossing validation.
        exact("Parallel road overlap is not a junction.", "parallel_overlap");
        exact("A controller would obstruct the junction; move the crossing away from its anchor.",
                "controller_obstruct");
        exact("Layered crossings need a consistent upper road and at least 3 blocks of surface separation everywhere.",
                "layered_config");
        exact("The final bridge blocks intrude into the lower road's two-block walking clearance.", "bridge_intrudes");
        exact("A junction must be one level platform.", "junction_one_level");
        exact("Empty junction", "empty_junction");

        // Row height and profile.
        exact("Road anchor must be inside the endpoints.", "anchor_outside");
        exact("The anchor row must remain flat; edit that row to clear its old stair type.", "anchor_not_flat");
        exact("Anchor is outside the permitted profile.", "anchor_outside_profile");
        exact("A junction conflicts with the fixed anchor height.", "junction_anchor_conflict");
        exact("A required platform is outside the road or world height.", "platform_height");
        exact("A manually locked stair conflicts with a flat platform.", "locked_stair_conflict");
        exact("No profile can connect the fixed heights with the available rows and manual edits.", "no_profile");
        exact("Road planning exceeds the computation budget.", "computation_budget");

        // Automatic planning.
        exact("Unsupported automatic road length.", "bad_length");
        exact("Load the entire road before automatic planning.", "chunks_not_loaded");
        exact("Water terrain needs manual road planning.", "fluid_blocked");
        exact("No reliable road foundation found; use manual planning.", "no_surface");
        exact("Choose which road passes above before automatic clearance balancing.", "choose_order");
        exact("Nearby road platforms require conflicting heights.", "platform_conflict");
        exact("Crossing height constraints conflict.", "crossing_height_conflict");

        // Endpoints and relation changes.
        exact("Endpoints must share exactly one coordinate (x or z).", "endpoints_axis");
        exact("Road anchor is outside the endpoint interval.", "anchor_outside");
        exact("Road anchor must lie on the AB line.", "anchor_off_line");
        exact("Invalid relation change.", "invalid_mode");
        exact("Same-level junction changes require the neighbor's approval.", "needs_approval");

        // Crossing consent and repair.
        exact("Road removed.", "road_removed");
        exact("Related road removed.", "peer_removed");
        exact("Main road management permission was revoked.", "native_permission_revoked");
        exact("Native management permission was revoked.", "native_permission_revoked");
        exact("The repair changes the neighboring road; request approval again.", "repair_needs_approval");
        exact("Crossing consent expired; prepare again.", "consent_expired");
        exact("The native builder order was not accepted.", "builder_order_rejected");

        // Messages that append a value; the value is dropped from the notice.
        prefix("Lamp cell exceeds generated bounds", "cell_out_of_bounds");
        prefix("Road unit cell exceeds generated bounds", "cell_out_of_bounds");
        prefix("A related road cannot be changed now", "peer_unavailable");
        prefix("Conflicting native work targets", "native_targets_conflict");
        prefix("Junction height must match", "junction_height_mismatch");
        prefix("A crossing road is busy", "crossing_busy");
        prefix("The crossing on ", "crossing_mismatch");
        prefix("Road length ", "road_too_long");
    }

    /** Translation key for the given failure message, or {@code null} when unmapped. */
    public static String keyFor(final String message) {
        if (message == null || message.isEmpty()) return null;
        final String hit = EXACT.get(message);
        if (hit != null) return hit;
        for (final Map.Entry<String, String> entry : PREFIX.entrySet()) {
            if (message.startsWith(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static void exact(final String message, final String key) {
        EXACT.put(message, "rp_crossroads.reason." + key);
    }

    private static void prefix(final String message, final String key) {
        PREFIX.put(message, "rp_crossroads.reason." + key);
    }

    private RoadReasons() { }
}
