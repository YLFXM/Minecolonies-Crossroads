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

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Extracts a one-block-long slice from a straight road blueprint. The source
 * blueprint is never modified.
 */
public final class RoadUnitExtractor {
    public static UnitTemplate extract(final Blueprint source, final RoadUnit unit) {
        if (source == null) {
            return null;
        }

        final int sx = source.getSizeX();
        final int sy = source.getSizeY();
        final int sz = source.getSizeZ();
        final Direction.Axis axis = unit.axis();
        final int slice = unit.slice();

        final int outX = axis == Direction.Axis.X ? 1 : unit.width();
        final int outZ = axis == Direction.Axis.Z ? 1 : unit.width();
        final UnitTemplate template = new UnitTemplate(outX, sy, outZ, unit.surfaceY(), axis);

        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    final int along = axis == Direction.Axis.X ? x : z;
                    if (along != slice) {
                        continue;
                    }
                    // A unit owns only part of the slice width: a tile owns all of
                    // it, a lamp owns just its own column plus the one the lantern
                    // hangs over.
                    final int lateral = axis == Direction.Axis.X ? z : x;
                    if (lateral < unit.lateralFrom() || lateral > unit.lateralTo()) {
                        continue;
                    }
                    final int localLateral = lateral - unit.lateralFrom();

                    final BlockPos local = axis == Direction.Axis.X
                            ? new BlockPos(0, y, localLateral)
                            : new BlockPos(localLateral, y, 0);

                    // A lamp slice carries two things that must not be copied:
                    // the foundation below the surface (the road template lays
                    // that down itself) and the pavement column next to the pole
                    // (it may be flat path or a stair, so it is claimed, never
                    // written). Everything above the surface is copied as-is.
                    if (unit.type() == RoadUnit.UnitType.LAMP) {
                        if (y < unit.surfaceY()) {
                            continue;
                        }
                        if (y == unit.surfaceY() && localLateral > 0) {
                            template.addPlaceholder(local);
                            continue;
                        }
                    }

                    final BlockPos srcPos = new BlockPos(x, y, z);
                    final BlockState state = source.getBlockState(srcPos);
                    if (state == null || state.isAir()) {
                        continue;
                    }

                    final ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    if ("minecolonies".equals(blockId.getNamespace()) && "decorationcontroller".equals(blockId.getPath())) {
                        // The style pack marks its own lamp column with a controller.
                        // Only one controller may exist per road and the road system
                        // places it, so this cell is actively emptied: a placeholder
                        // would tell the builder to leave whatever is there.
                        template.add(local, Blocks.AIR.defaultBlockState(), null);
                        continue;
                    }

                    CompoundTag te = null;
                    final CompoundTag sourceTe = source.getTileEntities()[y][z][x];
                    if (sourceTe != null) {
                        te = sourceTe.copy();
                        te.putInt("x", local.getX());
                        te.putInt("y", local.getY());
                        te.putInt("z", local.getZ());
                    }

                    template.add(local, state, te);
                }
            }
        }

        return template;
    }

    /**
     * Resolves a block id to a state, defaulting to stone if the id is invalid.
     */
    public static BlockState blockState(final String id) {
        final Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        if (block == null || block.defaultBlockState().isAir()) {
            return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        }
        return block.defaultBlockState();
    }

    private RoadUnitExtractor() {
    }
}
