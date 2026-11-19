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
package com.YLFXM.rp.crossroads.client;

/**
 * What the wand is currently selecting: a road, a dig volume or a fill volume.
 *
 * <p>The mode is what the two "挖掘 / 填充选区" buttons switch. It replaces the
 * road the wand was bound to, the same way a bind replaces the previous road, and
 * the panel's build button follows it: road mode builds the road, terrain mode
 * turns the two corners into a terrain blueprint and opens the native tool.</p>
 *
 * <p>Client-side only. The server learns the mode from the build request, which
 * is the only point where it matters — the box on screen is drawn locally from
 * the same A/B the player is picking.</p>
 */
public final class TerrainClientState {
    public enum Mode { NONE, DIG, FILL }

    private static volatile Mode mode = Mode.NONE;

    public static Mode mode() {
        return mode;
    }

    public static boolean isTerrain() {
        return mode != Mode.NONE;
    }

    /** @return true when the mode actually changed */
    public static boolean setMode(final Mode value) {
        final Mode next = value == null ? Mode.NONE : value;
        if (next == mode) {
            return false;
        }
        mode = next;
        return true;
    }

    public static void clear() {
        mode = Mode.NONE;
    }

    private TerrainClientState() { }
}
