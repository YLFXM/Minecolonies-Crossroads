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
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Writing cells into a generated blueprint.
 *
 * <p>{@code Blueprint.addBlockState} indexes a {@code short[y][z][x]} array, so an
 * out-of-range position throws {@link ArrayIndexOutOfBoundsException}. Each call site
 * therefore has to decide what a miss means, and there are three real answers:</p>
 *
 * <ul>
 *   <li>{@link #write} — a miss is normal (a junction decorates cells of a wider
 *       neighbour that fall outside this road's image): skip silently, return false.</li>
 *   <li>{@link #writeUnchecked} — the caller owns the bounds and guarantees a hit; a
 *       miss is a programming error and is allowed to throw.</li>
 *   <li>the road generator keeps its own check against {@link
 *       com.YLFXM.rp.crossroads.road.RoadBounds}, because a miss there means the
 *       generated image would be missing cells and must refuse the whole build.</li>
 * </ul>
 *
 * <p>Do not collapse these into one policy: skipping silently in the generator ships a
 * blueprint with holes, and throwing in junction decoration fails every crossing whose
 * neighbour is wider than its own road.</p>
 */
public final class BlueprintCells {
    public static boolean inside(final Blueprint blueprint, final BlockPos pos) {
        return inside(blueprint.getSizeX(), blueprint.getSizeY(), blueprint.getSizeZ(), pos);
    }

    /** Size-only form, so the containment rule can be exercised without a blueprint instance. */
    public static boolean inside(final int sizeX, final int sizeY, final int sizeZ, final BlockPos pos) {
        return pos.getX() >= 0 && pos.getY() >= 0 && pos.getZ() >= 0
                && pos.getX() < sizeX && pos.getY() < sizeY && pos.getZ() < sizeZ;
    }

    /** Writes when inside; returns whether anything was written. */
    public static boolean write(final Blueprint blueprint, final BlockPos pos, final BlockState state) {
        return write(blueprint, pos, state, null);
    }

    /** Writes when inside, replacing any tile entity at that cell. */
    public static boolean write(final Blueprint blueprint, final BlockPos pos, final BlockState state,
                                final CompoundTag tileEntity) {
        if (!inside(blueprint, pos)) return false;
        blueprint.addBlockState(pos, state);
        blueprint.getTileEntities()[pos.getY()][pos.getZ()][pos.getX()] = null;
        if (tileEntity != null) {
            blueprint.getTileEntities()[pos.getY()][pos.getZ()][pos.getX()] = tileAt(tileEntity, pos);
        }
        return true;
    }

    /** No bounds check: the caller guarantees the cell exists. Out of range throws. */
    public static void writeUnchecked(final Blueprint blueprint, final BlockPos pos, final BlockState state,
                                      final CompoundTag tileEntity) {
        blueprint.addBlockState(pos, state);
        blueprint.getTileEntities()[pos.getY()][pos.getZ()][pos.getX()] =
                tileEntity == null ? null : tileAt(tileEntity, pos);
    }

    /** Bottom-left world position of a blueprint image, derived from its anchor block. */
    public static BlockPos origin(final RoadPlan plan, final Blueprint blueprint) {
        return origin(plan.anchorPos(), blueprint);
    }

    public static BlockPos origin(final BlockPos anchor, final Blueprint blueprint) {
        return anchor.subtract(blueprint.getPrimaryBlockOffset());
    }

    /**
     * Old concrete content the new bounded blueprint explicitly replaces with air.
     *
     * <p>Only cells inside the new image count: a cell outside it is not being rewritten,
     * so it must not be queued for clearing.</p>
     */
    public static List<BlockPos> clearTargets(final RoadPlan plan, final Blueprint old, final Blueprint target) {
        if (old == null) return List.of();
        final BlockPos oldOrigin = origin(plan, old), newOrigin = origin(plan, target);
        final List<BlockPos> positions = new ArrayList<>();
        for (final var info : old.getBlockInfoAsList()) {
            if (!RoadBlocks.isConcrete(info.getState())) continue;
            final var world = oldOrigin.offset(info.getPos());
            if (world.equals(plan.anchorPos()) || world.equals(plan.controllerPos())) continue;
            final var local = world.subtract(newOrigin);
            if (local.getX() < 0 || local.getY() < 0 || local.getZ() < 0 || local.getX() >= target.getSizeX()
                    || local.getY() >= target.getSizeY() || local.getZ() >= target.getSizeZ()) continue;
            if (target.getBlockState(local).isAir()) positions.add(world);
        }
        return List.copyOf(positions);
    }

    private static CompoundTag tileAt(final CompoundTag source, final BlockPos pos) {
        final CompoundTag tile = source.copy();
        tile.putInt("x", pos.getX());
        tile.putInt("y", pos.getY());
        tile.putInt("z", pos.getZ());
        return tile;
    }

    private BlueprintCells() {
    }
}
