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
package com.YLFXM.mcreconstruction.client;

import com.minecolonies.core.blocks.BlockDecorationController;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Shift-right with a Wand binds; plain right-click opens our controller panel.
 * Both must suppress the vanilla decoration GUI.
 */
@EventBusSubscriber(modid = com.YLFXM.mcreconstruction.MCReconstruction.MODID, value = Dist.CLIENT)
public final class ClientDecorationControllerEvents {
    @SubscribeEvent
    public static void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()
                || !(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof BlockDecorationController)) {
            return;
        }

        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        if(event.getHand()==net.minecraft.world.InteractionHand.MAIN_HAND)
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.YLFXM.mcreconstruction.network.RequestControllerWindowPayload(event.getPos()));
    }

    private ClientDecorationControllerEvents() {
    }
}
