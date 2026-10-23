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

    private RoadBlueprintOps() { }
}
