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
package com.YLFXM.rp.crossroads.road.common;

import com.YLFXM.rp.crossroads.road.RoadBounds;
import com.YLFXM.rp.crossroads.road.RoadPlan;
import net.minecraft.core.Direction;

/**
 * XZ footprint of a road: the row range it covers times the lateral columns it occupies.
 *
 * <p>Contains no Y: reserved lamp height and terrain relief belong to the road model,
 * not to the footprint. Callers that need a box use {@link RoadBounds} and project it
 * with {@link #xz}.</p>
 */
public final class RoadGeometry {
    public record Rect(int minX, int minZ, int maxX, int maxZ) {
        public Rect {
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Empty footprint");
        }

        public boolean contains(final int x, final int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        /** Overlap of two footprints, or {@code null} when they do not meet. */
        public Rect intersect(final Rect b) {
            final int x = Math.max(minX, b.minX), z = Math.max(minZ, b.minZ);
            final int xx = Math.min(maxX, b.maxX), zz = Math.min(maxZ, b.maxZ);
            return x > xx || z > zz ? null : new Rect(x, z, xx, zz);
        }

        /** First row of this rectangle along the plan's own axis. */
        public int first(final RoadPlan plan) {
            return RoadAxisMath.along(plan.axis(), minX, minZ);
        }

        /** Last row of this rectangle along the plan's own axis. */
        public int last(final RoadPlan plan) {
            return RoadAxisMath.along(plan.axis(), maxX, maxZ);
        }

        /** Serializes the four XZ bounds; used by the per-dimension junction table. */
        public net.minecraft.nbt.CompoundTag save() {
            final net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putIntArray("rect", new int[]{minX, minZ, maxX, maxZ});
            return tag;
        }

        public static Rect load(final net.minecraft.nbt.CompoundTag tag) {
            final int[] values = tag.getIntArray("rect");
            if (values.length != 4) throw new IllegalArgumentException("Rect needs four bounds");
            return new Rect(values[0], values[1], values[2], values[3]);
        }
    }

    /** Full paved width, every lateral column the road owns. */
    public static Rect body(final RoadPlan plan) {
        return footprint(plan, 0, plan.width() - 1);
    }

    /** Walkable columns only; differs from {@link #body} when the road has kerbs. */
    public static Rect walk(final RoadPlan plan) {
        return footprint(plan, plan.walkRange().from(), plan.walkRange().to());
    }

    public static Rect footprint(final RoadPlan plan, final int firstLateral, final int lastLateral) {
        final int base = RoadAxisMath.crossBase(plan);
        final int a = base + plan.widthSide() * firstLateral;
        final int b = base + plan.widthSide() * lastLateral;
        return plan.axis() == Direction.Axis.X
                ? new Rect(plan.minAlong(), Math.min(a, b), plan.maxAlong(), Math.max(a, b))
                : new Rect(Math.min(a, b), plan.minAlong(), Math.max(a, b), plan.maxAlong());
    }

    /** XZ projection of a 3D box. */
    public static Rect xz(final RoadBounds bounds) {
        return new Rect(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
    }

    private RoadGeometry() {
    }
}
