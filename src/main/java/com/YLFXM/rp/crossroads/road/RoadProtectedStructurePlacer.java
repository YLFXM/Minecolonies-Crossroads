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
package com.YLFXM.rp.crossroads.road;

import java.util.function.Supplier;
import com.ldtteam.structurize.placement.*;
import com.ldtteam.structurize.placement.structure.IStructureHandler;
import com.ldtteam.structurize.util.ChangeStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Makes blockSubstitution a true no-op in every creative native phase, including Pretty clearing. */
public final class RoadProtectedStructurePlacer extends StructurePlacer {
    public RoadProtectedStructurePlacer(IStructureHandler handler) { super(handler); }
    @Override public StructurePhasePlacementResult executeStructureStep(Level world,ChangeStorage storage,BlockPos start,
            Operation operation,Supplier<AbstractBlueprintIterator.Result> advance,boolean entities) {
        Supplier<AbstractBlueprintIterator.Result> filtered=() -> {
            AbstractBlueprintIterator.Result result=advance.get();
            while(result==AbstractBlueprintIterator.Result.NEW_BLOCK && handler.getBluePrint().getBlockState(iterator.getProgressPos())
                    .is(com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get())) result=advance.get();
            return result;
        };
        return super.executeStructureStep(world,storage,start,operation,filtered,entities);
    }
}
