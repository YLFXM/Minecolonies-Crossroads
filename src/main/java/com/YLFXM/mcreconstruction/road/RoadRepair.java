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
import com.ldtteam.structurize.util.BlockInfo;
import com.YLFXM.mcreconstruction.Config;
import com.YLFXM.mcreconstruction.MCReconstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Repairs a road by re-applying the saved/generated blueprint. This is the
 * post-build Repair action; builder-based repair can be added later.
 */
public final class RoadRepair {
    public static boolean repair(final ServerLevel level, final RoadPlan plan) {
        // Repair is the moment a re-pick becomes real: a re-pick only stages the
        // filtered table, and this is where it replaces the committed one. When
        // that happens the cached blueprint is stale by definition, so it must be
        // regenerated rather than loaded.
        final boolean appliedPending = plan.applyPendingRows();
        if (appliedPending) {
            MCReconstruction.LOGGER.info("Road {} repair: committed {} staged row(s)",
                    plan.id(), plan.confirmedRowCount());
        }
        Blueprint blueprint = appliedPending ? null : RoadBlueprintStore.load(level, plan);
        if (blueprint == null) {
            blueprint = RoadBlueprintGenerator.generate(plan, level, Config.defaultLampReserveHeight());
            if (blueprint != null) {
                RoadBlueprintStore.save(level, plan, blueprint);
            }
        }
        if (blueprint == null) {
            return false;
        }

        final BlockPos origin = RoadBlueprintOps.origin(plan, blueprint);
        // The builder resolves `blocksolidsubstitution` from its own fill settings;
        // this path places real blocks, so it has to resolve it itself. Plain
        // `blocksubstitution` keeps being skipped — it means "leave this cell
        // alone" (it is the reserved controller cell / preserved pavement).
        // Support cells resolve to the local ground instead of a fixed block,
        // mirroring how the builder fills with its fill setting rather than one
        // hardcoded material.
        int repaired = 0;
        for (final BlockInfo info : blueprint.getBlockInfoAsList()) {
            final BlockPos world = origin.offset(info.getPos());
            final BlockState state = RoadBlueprintOps.isSolidFill(info.getState())
                    ? supportFiller(level, world)
                    : info.getState();
            if (!RoadBlueprintOps.isConcrete(state)) {
                continue;
            }
            if (!level.getBlockState(world).equals(state)) {
                level.setBlock(world, state, 3);
                repaired++;
            }
            if (info.hasTileEntityData() && info.getTileEntityData() != null) {
                final BlockEntity be = level.getBlockEntity(world);
                if (be != null) {
                    final CompoundTag tag = info.getTileEntityData().copy();
                    tag.putInt("x", world.getX());
                    tag.putInt("y", world.getY());
                    tag.putInt("z", world.getZ());
                    be.loadWithComponents(tag, level.registryAccess());
                    be.setChanged();
                }
            }
        }
        plan.setAllowBlueprintEdit(false);
        RoadBuildWatcher.markBuilt(level, plan);
        MCReconstruction.LOGGER.info("Repaired road {} ({} blocks)", plan.id(), repaired);
        return true;
    }

    /**
     * Block to use under the pavement.
     *
     * <p>Keeps whatever solid ground already occupies the cell, and for empty
     * cells copies the nearest solid terrain below. That reproduces the
     * builder's "fill with the local ground" behaviour instead of stamping a
     * fixed material; the config value is only the last resort.</p>
     */
    private static BlockState supportFiller(final ServerLevel level, final BlockPos pos) {
        final BlockState existing = level.getBlockState(pos);
        if (!existing.isAir() && existing.isSolid()) {
            return existing;
        }
        for (int dy = 1; dy <= 4; dy++) {
            final BlockPos below = pos.below(dy);
            final BlockState state = level.getBlockState(below);
            if (!state.isAir() && state.isSolid() && RoadBlueprintOps.isConcrete(state)) {
                return state;
            }
        }
        return RoadUnitExtractor.blockState(Config.roadFillBlock());
    }

    private RoadRepair() {
    }
}
