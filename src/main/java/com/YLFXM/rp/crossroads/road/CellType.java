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

/**
 * Per-cell override for one road cell.
 *
 * <p>Stair directions are relative to the plan's travel direction, A towards B:
 * a {@link #STAIR_UP} cell makes the surface climb by one block as the road
 * advances from A to B. Storing the direction relative to travel is what keeps a
 * cell's meaning stable when the player re-selects A and B the other way round —
 * the physical slope must not flip just because the endpoints were clicked in a
 * different order.</p>
 */
public enum CellType {
    /** Plain surface at the current level. */
    FLAT,
    /** One-block ascent in the travel direction. */
    STAIR_UP,
    /** One-block descent in the travel direction. */
    STAIR_DOWN;

    public boolean isStair() {
        return this != FLAT;
    }

    /** Vertical change this cell applies to the cells after it. */
    public int rise() {
        return switch (this) {
            case STAIR_UP -> 1;
            case STAIR_DOWN -> -1;
            case FLAT -> 0;
        };
    }
}
