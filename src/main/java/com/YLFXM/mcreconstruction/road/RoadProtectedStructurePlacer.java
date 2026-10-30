/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 ZhuanZ (YLFXM) */
package com.YLFXM.mcreconstruction.road;

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
