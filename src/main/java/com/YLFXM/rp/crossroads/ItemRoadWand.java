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

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Selection wand for road endpoints. Left click sets A; shift-left sets B;
 * right click edits the aimed row (road mode) or dispatches the terrain order
 * (dig/fill mode); shift-right cycles the mode.
 *
 * <p>The wand opens no window: everything it used to edit lives in the
 * controller panel now, and the remaining gestures are handled by
 * {@code client.ClientSelectionEvents}, which owns both the block and the air
 * path for them.</p>
 */
public class ItemRoadWand extends Item {
    public ItemRoadWand(final Properties properties) {
        super(properties);
    }

    @Override
    public boolean canAttackBlock(final BlockState state, final Level level, final BlockPos pos, final Player player) {
        // The wand uses left-click to select endpoint A; never break blocks.
        return false;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(final Level level, final Player player, final InteractionHand hand) {
        // Deliberately inert on both sides. Terrain dispatch is a client gesture
        // (it needs the local A/B and mode) and runs from
        // ClientSelectionEvents.RightClickItem; keeping the item free of client
        // types is what lets the dedicated server load this class.
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }
}
