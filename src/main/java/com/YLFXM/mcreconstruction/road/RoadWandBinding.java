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

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Stores the bound road-controller position on the Road Wand itself.
 */
public final class RoadWandBinding {
    private static final String KEY = "mcreconstruction:road_controller";

    public static void bind(final ItemStack stack, final BlockPos controllerPos) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putLong(KEY, controllerPos.asLong()));
    }

    public static void unbind(final ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(KEY));
    }

    public static BlockPos heldController(final net.minecraft.world.entity.player.Inventory inventory) {
        final ItemStack main = inventory.getSelected();
        if (main.is(com.YLFXM.mcreconstruction.ModRegistry.ROAD_WAND.get())) return getBoundController(main);
        final ItemStack off = inventory.offhand.get(0);
        return off.is(com.YLFXM.mcreconstruction.ModRegistry.ROAD_WAND.get()) ? getBoundController(off) : null;
    }

    public static BlockPos getBoundController(final ItemStack stack) {
        final CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(KEY)) {
            return null;
        }
        final long packed = data.copyTag().getLong(KEY);
        return packed == 0L ? null : BlockPos.of(packed);
    }

    public static java.util.List<ItemStack> wands(final net.minecraft.world.entity.player.Inventory inventory) {
        final java.util.List<ItemStack> result = new java.util.ArrayList<>();
        final java.util.Set<ItemStack> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (final ItemStack stack : java.util.List.of(inventory.getSelected(), inventory.offhand.get(0))) {
            if (stack.is(com.YLFXM.mcreconstruction.ModRegistry.ROAD_WAND.get()) && seen.add(stack)) result.add(stack);
        }
        for (final ItemStack stack : inventory.items) {
            if (stack.is(com.YLFXM.mcreconstruction.ModRegistry.ROAD_WAND.get()) && seen.add(stack)) result.add(stack);
        }
        return result;
    }

    public static BlockPos findController(final net.minecraft.world.entity.player.Inventory inventory) {
        for (final ItemStack stack : wands(inventory)) {
            final BlockPos bound = getBoundController(stack);
            if (bound != null) return bound;
        }
        return null;
    }

    public static ItemStack findBound(final net.minecraft.world.entity.player.Inventory inventory,
                                     final BlockPos controller) {
        for (final ItemStack stack : wands(inventory)) {
            if (controller.equals(getBoundController(stack))) return stack;
        }
        return null;
    }

    public static boolean rebind(final net.minecraft.world.entity.player.Inventory inventory,
                                 final BlockPos previous, final BlockPos target) {
        boolean changed = false;
        for (final ItemStack stack : wands(inventory)) {
            if (previous.equals(getBoundController(stack))) {
                bind(stack, target);
                changed = true;
            }
        }
        return changed;
    }

    private RoadWandBinding() {
    }
}
