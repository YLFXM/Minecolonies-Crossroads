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

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class RoadRenderRange {
    public static boolean contains(final AABB bounds, final Vec3 camera) {
        // Read the setting every time: it is a plain int getter, and caching it
        // would need invalidation when the config is reloaded.
        final double distance = com.YLFXM.rp.crossroads.Config.ghostRenderDistance();
        return bounds.distanceToSqr(camera) <= distance * distance;
    }
    private RoadRenderRange() { }
}
