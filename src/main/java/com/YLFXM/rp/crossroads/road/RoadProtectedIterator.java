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

import com.ldtteam.structurize.placement.*;
import com.ldtteam.structurize.placement.structure.IStructureHandler;
import com.ldtteam.structurize.util.BlueprintPositionInfo;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.common.util.TriPredicate;

/** Native builder iterator with exact no-touch cells filtered in every phase. */
public final class RoadProtectedIterator extends BlueprintIteratorDefault {
    public static final String ID="rp_crossroads:protected";
    public RoadProtectedIterator(IStructureHandler handler) {super(handler);}
    private static boolean protectedCell(BlueprintPositionInfo info) {
        return info.getBlockInfo().getState().is(com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get());
    }
    @Override public Result increment(TriPredicate<BlueprintPositionInfo,BlockPos,IStructureHandler> skip) {
        return super.increment((info,pos,handler) -> protectedCell(info) || skip.test(info,pos,handler));
    }
    @Override public Result decrement(TriPredicate<BlueprintPositionInfo,BlockPos,IStructureHandler> skip) {
        return super.decrement((info,pos,handler) -> protectedCell(info) || skip.test(info,pos,handler));
    }
    public static void register() {StructureIterators.registerIterator(ID,RoadProtectedIterator::new);}
    public static void protectOrders(RoadPlan plan) {
        for(var colony:com.minecolonies.api.colony.IColonyManager.getInstance().getAllColonies()) {
            if(!RoadWorkOrders.sameDimension(plan,colony))continue;
            for(var order:colony.getWorkManager().getWorkOrders().values())
                if(RoadWorkOrders.matches(plan,order) && order instanceof com.minecolonies.core.colony.workorders.AbstractWorkOrder nativeOrder)nativeOrder.setIteratorType(ID);
        }
    }
}
