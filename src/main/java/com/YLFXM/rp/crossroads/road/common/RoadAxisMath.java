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

import com.YLFXM.rp.crossroads.road.RoadPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Along/cross coordinate conversion for axis-aligned roads.
 *
 * <p>A road runs along one horizontal axis; {@link #along} is the travel direction,
 * {@link #cross} is perpendicular to it. The two are mirror images, so a call written
 * with the wrong one compiles fine and is only off by one cell at runtime. They are
 * kept as a named pair with no other overloads for that reason.</p>
 */
public final class RoadAxisMath {
    /** Travel-direction coordinate: X-axis roads use x, Z-axis roads use z. */
    public static int along(final Direction.Axis axis, final int x, final int z) {
        return axis == Direction.Axis.X ? x : z;
    }

    /** Perpendicular coordinate: X-axis roads use z, Z-axis roads use x. */
    public static int cross(final Direction.Axis axis, final int x, final int z) {
        return axis == Direction.Axis.X ? z : x;
    }

    public static int alongOf(final Direction.Axis axis, final BlockPos pos) {
        return along(axis, pos.getX(), pos.getZ());
    }

    public static int crossOf(final Direction.Axis axis, final BlockPos pos) {
        return cross(axis, pos.getX(), pos.getZ());
    }

    public static BlockPos blockPos(final Direction.Axis axis, final int along, final int cross, final int y) {
        return axis == Direction.Axis.X ? new BlockPos(along, y, cross) : new BlockPos(cross, y, along);
    }

    /**
     * Cross coordinate of the anchor column.
     *
     * <p>Every lateral offset of a road is measured from here, never from the segment
     * start: mirroring flips the road around the anchor, so the two only agree while
     * they happen to coincide.</p>
     */
    public static int crossBase(final RoadPlan plan) {
        return crossOf(plan.axis(), plan.anchorPos());
    }

    private RoadAxisMath() {
    }
}
