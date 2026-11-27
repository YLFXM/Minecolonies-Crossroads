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
package com.YLFXM.rp.crossroads;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The Road Wand is a selection tool, not a mining tool. Cancel block breaking
 * on both logical sides so neither mode can destroy the clicked block.
 *
 * <p>This is also the guard for the held button: after the first press the client
 * keeps calling {@code continueDestroyBlock}, which fires {@code LeftClickBlock}
 * again every tick. Corner selection reads the input event instead (once per
 * press), but the held path still has to be refused here or survival mining would
 * dig through the selection.</p>
 *
 * <p>Right-click is deliberately not cancelled here: shift-right on a controller
 * is handled by DecorationControllerHandler for binding.</p>
 */
@EventBusSubscriber(modid = Crossroads.MODID)
public final class RoadWandHandler {
    @SubscribeEvent
    public static void onLeftClickBlock(final PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity().getMainHandItem().is(ModRegistry.ROAD_WAND.get())) {
            event.setCanceled(true);
        }
    }

    private RoadWandHandler() {
    }
}
