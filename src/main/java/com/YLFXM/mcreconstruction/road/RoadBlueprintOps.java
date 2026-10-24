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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Block classification shared by consumers; each consumer retains its own write policy. */
public final class RoadBlueprintOps {
    public static BlockPos origin(final RoadPlan plan, final Blueprint blueprint) {
        return origin(plan.anchorPos(), blueprint);
    }

    public static BlockPos origin(final BlockPos anchor, final Blueprint blueprint) {
        return anchor.subtract(blueprint.getPrimaryBlockOffset());
    }

    public static boolean isPlaceholder(final BlockState state) {
        final var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "structurize".equals(id.getNamespace()) && id.getPath().contains("substitution");
    }

    public static boolean isSolidFill(final BlockState state) {
        final var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "structurize".equals(id.getNamespace()) && "blocksolidsubstitution".equals(id.getPath());
    }

    public static boolean isConcrete(final BlockState state) {
        return !state.isAir() && !isPlaceholder(state);
    }

    /** Ignore environmental shape/water updates, but never a stair facing/half mismatch. */
    public static boolean requiredStateMatches(final BlockState expected,final BlockState actual) {
        if(expected.getBlock()!=actual.getBlock()) return false;
        final var facing=net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        final var half=net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF;
        if(expected.hasProperty(facing) && expected.hasProperty(half))
            return expected.getValue(facing)==actual.getValue(facing) && expected.getValue(half)==actual.getValue(half);
        return true;
    }

    /** Old concrete content the new bounded blueprint explicitly replaces with air. */
    public static java.util.List<BlockPos> clearTargets(final RoadPlan plan,final Blueprint old,final Blueprint target) {
        if(old==null) return java.util.List.of();
        final BlockPos oldOrigin=origin(plan,old),newOrigin=origin(plan,target);
        final var positions=new java.util.ArrayList<BlockPos>();
        for(final var info:old.getBlockInfoAsList()) {
            if(!isConcrete(info.getState())) continue;
            final var world=oldOrigin.offset(info.getPos());
            if(world.equals(plan.anchorPos()) || world.equals(plan.controllerPos())) continue;
            final var local=world.subtract(newOrigin);
            if(local.getX()<0 || local.getY()<0 || local.getZ()<0 || local.getX()>=target.getSizeX()
                    || local.getY()>=target.getSizeY() || local.getZ()>=target.getSizeZ()) continue;
            if(target.getBlockState(local).isAir()) positions.add(world);
        }
        return java.util.List.copyOf(positions);
    }

    private RoadBlueprintOps() { }
}
