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
package com.YLFXM.mcreconstruction;

import net.minecraft.world.entity.player.Inventory;

/** Item-view identity shared by server replies and client tool switching. */
public final class RoadToolContext {
    public static String key(final Inventory inventory) {
        final var main = inventory.getSelected();
        final var off = inventory.offhand.get(0);
        if (main.is(ModRegistry.ROAD_SURVEYOR.get())) return "main:" + inventory.selected + ":surveyor:" + TownHallBinding.get(main);
        if (main.is(ModRegistry.ROAD_WAND.get())) return "main:" + inventory.selected + ":wand:" + com.YLFXM.mcreconstruction.road.RoadWandBinding.getBoundController(main);
        if (off.is(ModRegistry.ROAD_SURVEYOR.get())) return "off:surveyor:" + TownHallBinding.get(off);
        if (off.is(ModRegistry.ROAD_WAND.get())) return "off:wand:" + com.YLFXM.mcreconstruction.road.RoadWandBinding.getBoundController(off);
        return "none";
    }
    private RoadToolContext() { }
}
