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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
/** Removal entry point; physical demolition belongs to the native worker or creative queue. */
public final class RoadRemover {
    public static boolean remove(ServerLevel level,ServerPlayer player,RoadPlan plan,boolean instant) {
        return RoadNativeRemoval.begin(level,player,plan,instant);
    }
    private RoadRemover() { }
}
