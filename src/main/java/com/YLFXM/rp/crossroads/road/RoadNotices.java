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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * The single outlet for every player-facing notice in this mod.
 *
 * <p>Two channels, chosen by what the player needs from the message:</p>
 * <ul>
 *   <li>{@link #chat} — reasons, guidance, multi-argument summaries and clickable
 *       requests. Deduplicated per player + road + key for ten seconds so a
 *       repeated failure is reported once, not per attempt.</li>
 *   <li>{@link #actionBar} — short, immediate confirmations and validations the
 *       player does not need to reread. Not deduplicated: the action bar is
 *       overwritten by the next message anyway, and swallowing a second edit
 *       would look like the input was ignored.</li>
 * </ul>
 *
 * <p>Every message goes through {@code Component.translatable}; source code must
 * not build literal player text any more, and a failure's English message must not
 * reach a player either -- see {@link #reason}. Takes {@link Player} rather than
 * {@code ServerPlayer} so client-side gestures and windows can use the same outlet.</p>
 */
public final class RoadNotices {
    private static final long WINDOW = 10_000L;
    private static final Map<String, Long> SENT = new HashMap<>();

    /** Chat channel with the ten-second dedupe. */
    public static void chat(final Player player, final UUID road, final String key,
                            final String langKey, final Object... args) {
        send(player, road, key, text(langKey, args));
    }

    /** Action-bar channel; always shown. */
    public static void actionBar(final Player player, final String langKey, final Object... args) {
        if (player == null) return;
        player.displayClientMessage(text(langKey, args), true);
    }

    /**
     * Builds the translatable component, degrading to the raw key instead of throwing.
     *
     * <p>Translation arguments accept only {@code Component / Number / Boolean / String}.
     * Anything else throws while the message is being built — inside the packet path, so
     * the player is disconnected. A malformed argument must not cost a connection: it is
     * logged as an error and the key is shown as-is.</p>
     */
    private static Component text(final String langKey, final Object... args) {
        try {
            return Component.translatable(langKey, args);
        } catch (final RuntimeException ex) {
            com.YLFXM.rp.crossroads.Crossroads.LOGGER.error(
                    "Notice arguments rejected: key={} args={}", langKey,
                    java.util.Arrays.toString(args), ex);
            return Component.literal(langKey);
        }
    }

    /** Chat channel for a ready Component; the dedupe key is its rendered text. */
    public static void chatMessage(final Player player, final UUID road, final Component message) {
        send(player, road, "m:" + message.getString(), message);
    }

    /**
     * The player-facing reason behind a failure.
     *
     * <p>Exception messages are English and stay English (logs, and code that branches on
     * their text). Showing one to the player was the last untranslated surface left after
     * the notice unification: it read "道路蓝图无法生成：No bundled road unit matches ...".
     * Every reason is looked up through {@link RoadReasons}; an unmapped one renders the
     * generic internal-error line, whose text tells the player the detail is in the log.</p>
     */
    public static Component reason(final Throwable failure) {
        final String key = failure == null ? null : RoadReasons.keyFor(failure.getMessage());
        return text(key == null ? RoadReasons.INTERNAL : key);
    }

    /**
     * Locale-independent identity of a failure, for dedupe keys and stored panel text.
     *
     * <p>Never the English message: the same failure must dedupe to the same value for
     * every client language, and {@link #issue} needs a stable key to translate later.</p>
     */
    public static String reasonKey(final Throwable failure) {
        final String key = failure == null ? null : RoadReasons.keyFor(failure.getMessage());
        return key == null ? RoadReasons.INTERNAL : key;
    }

    /**
     * Renders stored panel text.
     *
     * <p>{@code RoadPlanningState.issue} keeps its string form (it is persisted in the plan
     * NBT, so its type cannot change), but what it holds is now a reason key. Older saves
     * still carry an English sentence; those are shown as-is rather than as a raw key.
     * A joined issue ("a b") is rendered part by part.</p>
     */
    public static Component issue(final String stored) {
        if (stored == null || stored.isEmpty()) return Component.literal("");
        final net.minecraft.network.chat.MutableComponent out = Component.empty();
        boolean first = true;
        for (final String part : stored.split(" ")) {
            if (!first) out.append(" ");
            out.append(part.startsWith("rp_crossroads.") ? text(part) : Component.literal(part));
            first = false;
        }
        return out;
    }

    /** Chat channel for a ready Component with an explicit dedupe key. */
    public static void send(final Player player, final UUID road, final String key,
                            final Component message) {
        if (player == null) return;
        final String dedupe = player.getUUID() + ":" + (road == null ? "-" : road.toString()) + ":" + key;
        final long now = System.currentTimeMillis();
        final Long previous = SENT.get(dedupe);
        if (previous != null && now - previous < WINDOW) return;
        SENT.put(dedupe, now);
        if (SENT.size() > 256) SENT.entrySet().removeIf(entry -> now - entry.getValue() > WINDOW);
        player.displayClientMessage(message, false);
    }

    /** Server stop: drop every dedupe entry. */
    public static void reset() {
        SENT.clear();
    }

    private RoadNotices() { }
}
