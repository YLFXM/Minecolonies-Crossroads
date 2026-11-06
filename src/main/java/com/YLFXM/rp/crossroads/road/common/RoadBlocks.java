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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * What a block means to a road, independent of which blueprint it came from.
 *
 * <p>Placeholders and solid fill are Structurize's own carriers: they exist to protect
 * or to be replaced, never to be seen. Kept apart from blueprint geometry so that
 * blueprint writing and block classification do not import each other's concerns.</p>
 */
public final class RoadBlocks {
    /** Resolves a block id to a state, defaulting to stone if the id is invalid. */
    public static BlockState blockState(final String id) {
        final Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        if (block == null || block.defaultBlockState().isAir()) {
            return Blocks.STONE.defaultBlockState();
        }
        return block.defaultBlockState();
    }

    /** Structurize substitution: a reserved cell, not pavement. */
    public static boolean isPlaceholder(final BlockState state) {
        final var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "structurize".equals(id.getNamespace()) && id.getPath().contains("substitution");
    }

    /** Structurize solid fill: terrain filler written by the road itself. */
    public static boolean isSolidFill(final BlockState state) {
        final var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "structurize".equals(id.getNamespace()) && "blocksolidsubstitution".equals(id.getPath());
    }

    public static boolean isStair(final BlockState state) {
        return state.hasProperty(BlockStateProperties.STAIRS_SHAPE);
    }

    public static boolean isConcrete(final BlockState state) {
        return !state.isAir() && !isPlaceholder(state);
    }

    /** Ignore environmental shape/water updates, but never a stair facing/half mismatch. */
    public static boolean requiredStateMatches(final BlockState expected, final BlockState actual) {
        if (expected.getBlock() != actual.getBlock()) return false;
        final var facing = BlockStateProperties.HORIZONTAL_FACING;
        final var half = BlockStateProperties.HALF;
        if (expected.hasProperty(facing) && expected.hasProperty(half)) {
            return expected.getValue(facing) == actual.getValue(facing)
                    && expected.getValue(half) == actual.getValue(half);
        }
        return true;
    }

    private RoadBlocks() {
    }
}
