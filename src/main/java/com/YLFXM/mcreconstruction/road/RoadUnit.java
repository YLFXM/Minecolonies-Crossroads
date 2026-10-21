/*
 * Copyright (C) 2026 ZhuanZ (YLFXM)
 *
 * MineColonies: Reconstruction Plan - a feature expansion addon for MineColonies.
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
package com.YLFXM.mcreconstruction.road;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Direction;

/**
 * Data-driven road unit definition. A unit is extracted from a style pack
 * blueprint by slice; no runtime blueprint is stored here.
 *
 * <p>A unit only describes <em>which</em> lateral columns of that slice it owns
 * ({@code lateralFrom}..{@code lateralTo}). A tile unit (road, stair) also
 * declares which of those columns are pavement ({@link WalkRange}); a lamp unit
 * does not, because a lamp owns no pavement — it is an overlay that is laid on
 * top of a road cell and must leave whatever the tile put there alone. That is
 * why {@code walkRange} is {@code null} for lamps rather than, say, a width of
 * zero.</p>
 */
public record RoadUnit(
        String id,
        UnitType type,
        String pack,
        String category,
        String source,
        Direction.Axis axis,
        int slice,
        int lateralFrom,
        int lateralTo,
        WalkRange walkRange,
        int surfaceY,
        int lampSideOffset) {

    public RoadUnit {
        if (lateralTo < lateralFrom) {
            throw new IllegalArgumentException("unit lateral span must not be inverted");
        }
        // A lamp is an overlay and owns no pavement; a tile is pavement and must
        // say which columns walkable. Allowing both to be sloppy here is exactly
        // how a lamp ends up overwriting the pavement it hangs over.
        final boolean overlay = type == UnitType.LAMP;
        if (overlay && walkRange != null) {
            throw new IllegalArgumentException("lamp units have no walk range");
        }
        if (!overlay && walkRange == null) {
            throw new IllegalArgumentException("tile units need a walk range");
        }
        if (walkRange != null && walkRange.to() > lateralTo - lateralFrom) {
            throw new IllegalArgumentException("walk range must stay inside the unit's lateral span");
        }
    }

    public enum UnitType {
        ROAD,
        STAIR,
        LAMP
    }

    /** True for units laid on top of a road cell instead of forming one. */
    public boolean isOverlay() {
        return type == UnitType.LAMP;
    }

    /** Number of lateral columns this unit occupies in the source blueprint. */
    public int width() {
        return lateralTo - lateralFrom + 1;
    }

    public static final class Registry {
        private static final Map<String, RoadUnit> UNITS = new LinkedHashMap<>();

        public static void register(final RoadUnit unit) {
            UNITS.put(unit.id(), unit);
        }

        public static RoadUnit get(final String id) {
            return UNITS.get(id);
        }

        public static Collection<RoadUnit> all() {
            return Collections.unmodifiableCollection(UNITS.values());
        }

        private Registry() {
        }
    }
}
